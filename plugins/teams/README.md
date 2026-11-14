# Microsoft Teams

`start_chat` uses Microsoft's documented chat deep link
([Deep link to a Teams chat](https://learn.microsoft.com/microsoftteams/platform/concepts/build-and-test/deep-link-teams)):
`https://teams.microsoft.com/l/chat/0/0?users=<emails>&message=<text>`. The message "only loads … into the chat
text box … and doesn't send the message automatically". The optional `topicName` (group chat name) is left out
because a template can't omit an empty query parameter, and an empty chat name is untested.

Known limitation: in January 2024 Microsoft reproduced a bug where Teams for Android showed "can't navigate to
the destination page" for this link
([Tech Community thread](https://techcommunity.microsoft.com/discussions/teamsdeveloper/deep-links-do-not-work-in-the-latest-mobile-teams-android/4025398)),
with no fix announced in that thread. `teams.microsoft.com` publishes no Android app-links file, so the link
only reaches the app because PhoneBridge pins it to the Teams package. Please test on a device before
upgrading the status.

Not tested on a device yet (`status: experimental`).
