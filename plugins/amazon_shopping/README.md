# Amazon Shopping

The app (`com.amazon.mShop.android.shopping`, version 32.7 from 2025) handles `VIEW` links for every path on
`www.amazon.<store>` for each store in the `store` parameter: see `DeepLinkingActivity` in a decompiled manifest
(<https://github.com/Pairs34/com.amazon.mShop.android.shopping_32.7.0.100>, file `AndroidManifest.xml`). The actions
open Amazon's public search (`/s?k=`) and product (`/dp/<ASIN>`) URLs there.

Known limitations:

* Not tested on a device: the app may show the page in its built-in web view rather than a native screen.
* Amazon India (`in.amazon.mShop.android.shopping`, amazon.in) is a different app and is not covered.
* A store that isn't the user's own may make the app switch country.
