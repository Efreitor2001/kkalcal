# Robokassa Android SDK: local security adaptation

Upstream: https://github.com/robokassa/sdk-android

Pinned upstream commit: ba251928cb4ebab30718d355d33b407d3d88a161

Reviewed on: 2026-10-17

License: MIT, Copyright (c) 2024 Robokassa; the complete upstream license is
retained in LICENSE.txt.

## Scope and provenance

This module is a small, locally maintained adaptation of the official SDK's
WebView payment-window and banking-app handoff approach. It is not an unchanged
official release, and it is not represented as an officially supported fork.
The primary upstream implementation reference is:

https://github.com/robokassa/sdk-android/blob/ba251928cb4ebab30718d355d33b407d3d88a161/Robokassa_Library/src/main/java/com/robokassa/library/view/RobokassaActivity.kt

The official SDK is distributed as source/a local AAR. No GitHub Releases were
published when reviewed. This module pins the upstream revision instead of
inventing a Maven coordinate or SDK version.

## Why the adaptation is necessary

The original BaseParams/PaymentParams API accepts the merchant's Password 1 and
Password 2, serializes them with payment parameters, and calculates payment and
status signatures on the device. Its optional logging includes signature input.
It cannot directly consume a payment URL signed by a merchant backend.

This module includes only the checkout UI. It does not include the original
credential API, signature code, payment-status API, recurrence/hold logic,
SharedPreferences persistence, Parcelable payment models, or log collection.
Merchant passwords and entitlement-signing keys must remain in backend secrets.

## Public contract

Module name: :robokassa-sdk

Package: ru.dietdiary.robokassa

RobokassaCheckout.createIntent(context, checkoutUrl, successUrl, failUrl,
postParams = null) opens a signed GET URL or submits an already URL-encoded and
signed POST body. The initial endpoint must be:

https://auth.robokassa.ru/Merchant/Index.aspx

The URL query/body may contain public merchant identifiers, an invoice number,
amount, receipt details and signatures. It must never contain merchant passwords.
POST requests use an endpoint without a query string. Both return URLs must be
absolute HTTPS URLs.

Use Android's StartActivityForResult contract, then pass its resultCode/data to
RobokassaCheckout.readOutcome. Outcomes are RETURNED, CANCELLED or ERROR.
Every outcome is only a UI/lifecycle signal. It never means an entitlement should
be unlocked. The host must authenticate its pending order to the backend and
validate the expected product/amount. Account ownership, if required, must be
implemented by the host; this checkout adapter does not authenticate Google
accounts or bind a licence to one.

## Browser and lifecycle changes

- The activity is not exported and has no external intent filters.
- Initial checkout accepts only the exact HTTPS Robokassa origin and endpoint.
- Return URLs compare the exact host/path and any preconfigured query selectors.
- The Robokassa status-page redirect is a return signal, not evidence of payment.
- HTTPS issuer/3-D Secure pages can load in the WebView. HTTP, mixed content,
  file/content access, automatic popups, JavaScript interfaces and downloads are
  disabled. SSL errors always cancel. Browser debugging and form autofill are
  disabled for checkout.
- SBP links to qr.nspk.ru open with ACTION_VIEW. Banking URI schemes are allowlisted
  and require a trusted Robokassa/NSPK launch origin. An intent URL is parsed only
  to obtain its validated data URI; component, selector, package, flags, extras
  and fallback URLs are discarded.
- No checkout payload is saved by this adapter. Android WebView's ordinary
  processor cookies/DOM storage remain available for the payment flow; this is
  not an incognito browser. The adapter does not clear the host app's global
  WebView cookie jar.
- Returning from a bank, closing the form or recovering from process death asks
  the host to reconcile its pending order. Rotation does not reload the form.

## Integration and verification limits

The host application registers its own verified app/deep links for its HTTPS
return pages and retains the pending backend order independently of this module.
Bank-app return signals can arrive through either that link or activity resume.

Unit tests exercise exact origins, redirect spoofing, banking schemes and
accidental secret-field rejection. A full payment must additionally be tested
with the actual merchant settings, ResultURL receiver, a physical bank app,
3-D Secure and an activated merchant. Robokassa's official documentation warns
that some SDK return behaviour and OpStateExt are unavailable for test payments.

References:

https://docs.robokassa.ru/ru/pay-interface

https://docs.robokassa.ru/ru/notifications-and-redirects

https://docs.robokassa.ru/ru/xml-interfaces

https://docs.robokassa.ru/ru/testing-mode
