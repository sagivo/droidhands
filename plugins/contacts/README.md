# Contacts

Both actions are Android's documented intents
([Modify contacts using intents](https://developer.android.com/identity/providers/contacts-provider/modify-data)):

* `add_contact`: `Intents.Insert.ACTION` (`android.intent.action.INSERT`) with the MIME type
  `RawContacts.CONTENT_TYPE`, filled with the `ContactsContract.Intents.Insert` extras (`name`, `phone`,
  `email`, `company`, `job_title`, `notes`). The contacts app shows the form; nothing is saved until Save.
* `add_to_contact`: `ACTION_INSERT_OR_EDIT` with the MIME type `Contacts.CONTENT_ITEM_TYPE` and no data URI: "the
  contacts app displays a list of contacts. Users can either insert a new contact or pick an existing contact".

`android.packages` lists Google Contacts, the AOSP app (emulators, some phones) and Samsung Contacts. Samsung is
included because these are platform intents rather than app-specific links, but nobody has checked it on a
Samsung phone yet.

Not tested on a device yet (`status: experimental`).
