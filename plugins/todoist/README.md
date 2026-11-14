# Todoist

All links are Todoist's documented mobile URL schemes (Todoist API docs, "Url schemes → Mobile app URL schemes",
[developer.todoist.com/api/v1](https://developer.todoist.com/api/v1/)): "Our applications for Android and iOS
support custom URL schemes".

* `add_task`: `todoist://addtask?content=…&date=…&priority=…`. "This URL scheme will not automatically submit
  the task to Todoist, it will just open and pre-fill the add task view." `priority` is the API value: 4 is
  very urgent (P1 in the app), 1 is normal (P4). The recipe always sends a priority (default 1) and an empty
  `date` when none is given, because templates can't leave a query parameter out.
* `search`: `todoist://search?query=…`, documented as Android only.
* `open_today`, `open_upcoming`, `open_inbox`: `todoist://today`, `todoist://upcoming`, `todoist://inbox`.

One user on r/todoist reported that the documented `addtask` example did not work for them, so check it on a
device before upgrading the status.

Not tested on a device yet (`status: experimental`).
