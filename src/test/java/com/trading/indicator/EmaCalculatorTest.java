package com.trading.indicator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for {@link EmaCalculator}.
 *
 * Covers:
 * <ul>
 *   <li>SMA seed computation</li>
 *   <li>Multiplier formula k = 2 / (period + 1)</li>
 *   <li>Full-series EMA with a known small dataset (period=3)</li>
 *   <li>Incremental {@link EmaCalculator#update} matches full recalculation</li>
 *   <li>600-period EMA on a 700-bar synthetic flat series (sanity check)</li>
 *   <li>600-period EMA on a 700-bar trending series (converges toward trend)</li>
 *   <li>Guard-rail: insufficient data throws {@link IllegalArgumentException}</li>
 *   <li>Guard-rail: null / negative inputs throw {@link IllegalArgumentException}</li>
 * </ul>
 */
class EmaCalculatorTest {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int PRICE_SCALE = 8;

    // -----------------------------------------------------------------------
    // SMA helper
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("SMA (seed) computation")
    class SmaTests {

        @Test
        @DisplayName("SMA of [1, 2, 3] should be 2")
        void smaSimple() {
            List<BigDecimal> values = List.of(bd("1"), bd("2"), bd("3"));
            BigDecimal result = EmaCalculator.sma(values);
            assertThat(result.setScale(2, RoundingMode.HALF_UP))
                    .isEqualByComparingTo(bd("2.00"));
        }

        @Test
        @DisplayName("SMA of a single value equals that value")
        void smaSingleValue() {
            List<BigDecimal> values = List.of(bd("150.50"));
            assertThat(EmaCalculator.sma(values).setScale(2, RoundingMode.HALF_UP))
                    .isEqualByComparingTo(bd("150.50"));
        }

        @Test
        @DisplayName("SMA of empty list throws IllegalArgumentException")
        void smaEmptyThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmaCalculator.sma(Collections.emptyList()));
        }
    }

    // -----------------------------------------------------------------------
    // Multiplier formula
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Multiplier k = 2 / (period + 1)")
    class MultiplierTests {

        @Test
        @DisplayName("Multiplier for period=3 should be 0.5")
        void multiplierPeriod3() {
            BigDecimal k = EmaCalculator.computeMultiplier(3);
            assertThat(k.setScale(4, RoundingMode.HALF_UP))
                    .isEqualByComparingTo(bd("0.5000"));
        }

        @Test
        @DisplayName("Multiplier for period=600 should be ~0.00332226")
        void multiplierPeriod600() {
            // k = 2 / 601 ≈ 0.00332778
            BigDecimal k = EmaCalculator.computeMultiplier(600);
            BigDecimal expected = bd("2").divide(bd("601"), MC);
            assertThat(k.subtract(expected).abs())
                    .isLessThan(bd("0.000001"));
        }
    }

    // -----------------------------------------------------------------------
    // Full-series EMA (small period=3 dataset)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Full-series EMA — period=3 known dataset")
    class FullSeriesTests {

        /**
         * Verified by hand:
         *
         * Prices: 10, 11, 12, 13, 14
         * Period: 3
         * k = 2/(3+1) = 0.5
         *
         * SMA(first 3) = (10+11+12)/3 = 11.0
         * EMA after bar 4 (price=13): 13*0.5 + 11*0.5 = 12.0
         * EMA after bar 5 (price=14): 14*0.5 + 12*0.5 = 13.0
         */
        @Test
        @DisplayName("EMA(3) on [10,11,12,13,14] should be 13.0")
        void knownDataset() {
            List<BigDecimal> prices = List.of(
                    bd("10"), bd("11"), bd("12"), bd("13"), bd("14")
            );
            BigDecimal ema = EmaCalculator.calculate(prices, 3);
            assertThat(ema.setScale(2, RoundingMode.HALF_UP))
                    .isEqualByComparingTo(bd("13.00"));
        }

        @Test
        @DisplayName("EMA(3) on exactly 3 values equals the SMA of those 3 values")
        void exactlyPeriodValues() {
            List<BigDecimal> prices = List.of(bd("10"), bd("20"), bd("30"));
            BigDecimal ema = EmaCalculator.calculate(prices, 3);
            assertThat(ema.setScale(2, RoundingMode.HALF_UP))
                    .isEqualByComparingTo(bd("20.00")); // SMA(10,20,30) = 20
        }

        @Test
        @DisplayName("Insufficient data throws IllegalArgumentException")
        void insufficientDataThrows() {
            List<BigDecimal> prices = List.of(bd("10"), bd("20")); // only 2 bars for period=3
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmaCalculator.calculate(prices, 3));
        }
    }

    // -----------------------------------------------------------------------
    // Incremental update — must match full recalculation
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Incremental update — consistency with full-series")
    class IncrementalUpdateTests {

        @Test
        @DisplayName("update() result matches calculate() result for period=3")
        void incrementalMatchesFull() {
            List<BigDecimal> allPrices = List.of(
                    bd("10"), bd("11"), bd("12"), bd("13"), bd("14"), bd("15")
            );
            int period = 3;

            // Full-series result
            BigDecimal fullResult = EmaCalculator.calculate(allPrices, period);

            // Incremental: seed on first 5, then update with the 6th
            BigDecimal seedEma = EmaCalculator.calculate(allPrices.subList(0, 5), period);
            BigDecimal incrementalResult = EmaCalculator.update(bd("15"), seedEma, period);

            assertThat(incrementalResult.setScale(PRICE_SCALE, RoundingMode.HALF_UP))
                    .isEqualByComparingTo(fullResult.setScale(PRICE_SCALE, RoundingMode.HALF_UP));
        }

        @Test
        @DisplayName("update() with zero newPrice throws IllegalArgumentException")
        void updateZeroPriceThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmaCalculator.update(BigDecimal.ZERO, bd("100"), 3));
        }

        @Test
        @DisplayName("update() with null previousEma throws IllegalArgumentException")
        void updateNullEmaThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmaCalculator.update(bd("100"), null, 3));
        }
    }

    // -----------------------------------------------------------------------
    // 600-EMA on 700-bar flat series (sanity check)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("600-EMA on synthetic 700-bar flat series")
    class FlatSeriesTests {

        @Test
        @DisplayName("On a perfectly flat series the EMA equals the constant price")
        void flatSeriesEmaEqualsConstantPrice() {
            BigDecimal constantPrice = bd("150.00");
            List<BigDecimal> prices = Collections.nCopies(700, constantPrice);

            BigDecimal ema = EmaCalculator.calculate(prices, 600);

            // EMA on a flat series must equal the constant price (within rounding)
            assertThat(ema.subtract(constantPrice).abs())
                    .isLessThan(bd("0.00001"));
        }
    }

    // -----------------------------------------------------------------------
    // 600-EMA on 700-bar trending series
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("600-EMA on synthetic 700-bar upward-trending series")
    class TrendingSeriesTests {

        @Test
        @DisplayName("On an upward-trending series the EMA is below the final price")
        void upTrendEmaLagsPrice() {
            // Prices rise linearly from 100 to 169 over 700 bars
            List<BigDecimal> prices = IntStream.range(0, 700)
                    .mapToObj(i -> bd("100").add(bd(String.valueOf(i)).multiply(bd("0.1"), MC)))
                    .collect(Collectors.toList());

            BigDecimal finalPrice = prices.get(prices.size() - 1);
            BigDecimal ema = EmaCalculator.calculate(prices, 600);

            // EMA lags price in an uptrend so it must be strictly below the final bar's close
            assertThat(ema).isLessThan(finalPrice);

            // But the EMA should not be wildly far away — stays within the range
            assertThat(ema).isGreaterThan(prices.get(0));
        }

        @Test
        @DisplayName("On a downward-trending series the EMA is above the final price")
        void downTrendEmaLagsPrice() {
            // Prices fall linearly from 200 to 131 over 700 bars
            List<BigDecimal> prices = IntStream.range(0, 700)
                    .mapToObj(i -> bd("200").subtract(bd(String.valueOf(i)).multiply(bd("0.1"), MC)))
                    .collect(Collectors.toList());

            BigDecimal finalPrice = prices.get(prices.size() - 1);
            BigDecimal ema = EmaCalculator.calculate(prices, 600);

            // EMA lags price in a downtrend — it should be above the final close
            assertThat(ema).isGreaterThan(finalPrice);
        }
    }

    // -----------------------------------------------------------------------
    // Guard-rails
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Input validation guard-rails")
    class GuardRailTests {

        @Test
        @DisplayName("Null prices list throws IllegalArgumentException")
        void nullPricesThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmaCalculator.calculate(null, 3));
        }

        @Test
        @DisplayName("Period of zero throws IllegalArgumentException")
        void zeroPeriodThrows() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmaCalculator.calculate(List.of(bd("10")), 0));
        }

        @Test
        @DisplayName("Period greater than list size throws IllegalArgumentException")
        void periodExceedsListSizeThrows() {
            List<BigDecimal> prices = List.of(bd("10"), bd("20"));
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> EmaCalculator.calculate(prices, 5));
        }
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
