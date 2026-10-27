# Cart Gate

An Android app that covers the pay button while your quick-commerce or food-delivery cart holds junk food. Take the item out and the button comes back. Mild versions (zero sugar, dark chocolate, baked chips, Coke Zero) go through.

Works with Zepto, Swiggy Instamart, Swiggy food and Zomato; Blinkit and Amazon are listed but not yet checked against a real cart.

**Status: preview.** The cart-reading engine is unit-tested against real screen dumps of the four apps above, and the engine it was extracted from has gated real carts in all four on an Android 13 phone. The 0.1.0 build itself has been run on that phone inside Zepto: the card came up over the pay bar with a full-sugar drink in the cart and lifted once the drink was removed ([screenshots](https://denzil10.github.io/lab/cart-gate/)). On an Android 15 emulator against a stand-in cart it also blocked taps on the pay button. If it misses a cart or covers the wrong spot, say so on [its Lab page](https://denzil10.github.io/lab/cart-gate/) or [open an issue](https://github.com/Denzil10/cart-gate/issues).

## Install

1. Download the APK from [Releases](https://github.com/Denzil10/cart-gate/releases).
2. Open Cart Gate, read what it does, tap **I agree, turn it on**.
3. In Accessibility settings, open Cart Gate and switch it on. On Android 13 and later a sideloaded app's switch can be greyed out: open App info → ⋮ → **Allow restricted settings**, then try again.

In the app you can switch off categories and apps, add your own words ("cheesecake, gol gappe"), and type a product name to see whether it would be blocked.

## How it decides

- A screen is a cart when it shows a pay button (`Pay now`, `Place order`, `Slide to pay`, …), the same wording across apps.
- An item counts only when a remove control, a `−` or its quantity sits on the same row. That keeps "You might also like" and "Complete your meal" suggestions from triggering it.
- Each row's text is matched as whole words against 11 categories (`app/src/main/assets/rules.json`). The first term in the text names the item, so "Thums Up … The Coca-Cola Company" is Thums Up. Mild words such as "zero sugar" let it through.
- The overlay sits from the top of the pay bar to the bottom of the screen and takes the taps, so the button under it cannot be pressed.

The tests (`app/src/test`) run that logic on the saved screens: `./gradlew test`.

## Privacy

- No internet permission: nothing it reads can leave the phone.
- Screen text is read only while one of the listed apps is in front, and only to find the pay button and the cart rows. Nothing is stored.
- The Accessibility service does nothing until you accept the disclosure in the app.

## Build

JDK 17 and the Android SDK (platform 35):

```sh
./gradlew test assembleDebug
```

Release builds are signed only when `CARTGATE_STORE_FILE` and `CARTGATE_STORE_PASSWORD` are set.

## License

MIT. Built by [Denzil Nelson](https://denzil10.github.io/).
