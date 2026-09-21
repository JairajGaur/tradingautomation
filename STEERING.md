# Kiro Crew Instruction Blueprint: Webull Java Trading Bot

## 🚀 System Architecture
- **Language & Framework:** Java 17, Spring Boot 3.x, Gradle (Groovy DSL).
- **Core Package:** `com.trading`
- **Target SDK:** Official Webull OpenAPI Java SDK (`com.webull.openapi:webull-openapi-java-sdk`).

## 🛡️ Guardrails & Safety Settings
1. **Environment Separation:** The system must look for a configuration flag `webull.trading.mode`.
2. **Default to Safety:** If the flag is not set, or is set to `PAPER`, all API endpoints must connect exclusively to the Webull Sandbox/Paper trading URLs. 
3. **Live Protection:** The `LIVE` trading endpoints must only be activated if explicitly enabled via a Spring profile (e.g., `prod-live`).
4. **Secret Management:** Never hardcode secrets. Inject the Webull App Key and App Secret using system environment variables (`WEBULL_APP_KEY` and `WEBULL_APP_SECRET`).

## 📈 Strategy Specification: 600-EMA Momentum
Implement a pluggable strategy pattern (e.g., a `TradingStrategy` interface) so we can add more strategies later. The first implementation must follow these rules:

1. **Asset & Timeframe:** 1-Minute Candlestick bars for any given equity ticker.
2. **Indicator Math:** 600-period Exponential Moving Average (600 EMA). The system must fetch at least 700 historical bars on startup to "warm up" the EMA calculation correctly.
3. **Entry Rule (BUY):** When a new 1-minute candle opens strictly ABOVE the 600 EMA line, trigger a market BUY order.
4. **Exit Rules (Bracket OCO Order):** Immediately upon a successful buy fill, calculate and submit two linked exit orders:
   - **Stop Loss:** A stop order set exactly 2% below the execution entry price.
   - **Take Profit:** A limit order set exactly 5% above the execution entry price.
5. **State Control:** Track open positions globally in memory so the bot does not fire multiple duplicate BUY orders for the same stock ticker while a position is already active.

## 🛠️ Tasks for Kiro Crew
1. Scan `build.gradle` and ensure Spring and Webull dependencies sync cleanly.
2. Generate the base Spring Boot application class (`TradingAutomationApplication.java`).
3. Build the configuration classes to initialize the Webull `TradeClientV2` bean.
4. Implement the 600-EMA strategy logic and calculation utilities using `BigDecimal` to prevent rounding errors.
5. Write a JUnit 5 test file with a mock dataset to verify the 600-EMA math works perfectly before any live connections are attempted.
