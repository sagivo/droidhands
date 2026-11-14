# Third-party notices

PhoneBridge is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)). Some of its files are
adapted from the third-party projects below, under their own licenses. Each such file starts with a
comment naming its origin.

## T3 Code

https://github.com/pingdotgg/t3code, MIT License.

| PhoneBridge file | Adapted from (T3 Code) |
|---|---|
| `clients/android/app/src/main/kotlin/dev/phonebridge/android/live/AgentActivityPresentation.kt` | `apps/mobile/modules/t3-agent-notifications/android/src/main/java/expo/modules/t3agentnotifications/AgentActivityPresentation.kt` (activity phases, summary and status-bar chip rules) |
| `clients/android/app/src/main/kotlin/dev/phonebridge/android/live/AgentActivityNotification.kt` | `apps/mobile/modules/t3-agent-notifications/android/src/main/java/expo/modules/t3agentnotifications/AgentNotifications.kt` (the Live Update notification: ongoing, silent, uncolorized, promotion request, short critical text, timeout, dismiss handling) |
| `clients/android/app/src/test/kotlin/dev/phonebridge/android/live/AgentActivityPresentationTest.kt` | `apps/mobile/modules/t3-agent-notifications/android/src/test/java/expo/modules/t3agentnotifications/AgentNotificationsTest.kt` |
| `clients/android/app/src/main/kotlin/dev/phonebridge/android/ui/deeplink/SetupQrPayload.kt` | `apps/mobile/src/features/connection/pairing.ts` (tolerant reading of a pairing URL from a QR payload) |
| `clients/android/app/src/test/kotlin/dev/phonebridge/android/ui/deeplink/SetupQrPayloadTest.kt` | `apps/mobile/src/features/connection/pairing.test.ts` |

```
MIT License

Copyright (c) 2026 T3 Tools Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
