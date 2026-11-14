# Viber

`open_bot_chat` uses the bot link documented in Viber's
[deep links](https://developers.viber.com/docs/tools/deep-links/) page: `viber://pa?chatURI=<URI>`, with the
optional `text` parameter that "pre-fills the chat's text input field". Opening it triggers a
`conversation_started` event, which gives the bot the user's name, avatar, language and country (Viber REST
API), so the action is `sensitive`.

Left out on purpose: `viber://chat?number=…` (open a chat with a phone number) and `viber://add?number=…`
appear in third-party guides with conflicting formats and are not documented by Viber. Plain text sharing is
covered by the discovered `share_text` action (Viber's share screen).

Not tested on a device yet (`status: experimental`).
