#!/usr/bin/env bash
# Seeds test contacts on the connected emulator/device via the contacts provider (adb shell content).
set -euo pipefail
ADB=${ADB:-/opt/homebrew/share/android-commandlinetools/platform-tools/adb}

add_contact() { # name phone email
  local name="$1" phone="$2" email="${3:-}"
  "$ADB" shell content insert --uri content://com.android.contacts/raw_contacts \
    --bind account_name:n: --bind account_type:n: >/dev/null
  local id
  id=$("$ADB" shell content query --uri content://com.android.contacts/raw_contacts --projection _id \
    | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | sort -n | tail -1)
  "$ADB" shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"$id" \
    --bind mimetype:s:vnd.android.cursor.item/name --bind "data1:s:'$name'" >/dev/null
  if [ -n "$phone" ]; then
    "$ADB" shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"$id" \
      --bind mimetype:s:vnd.android.cursor.item/phone_v2 --bind "data1:s:'$phone'" --bind data2:i:2 >/dev/null
  fi
  if [ -n "$email" ]; then
    "$ADB" shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:"$id" \
      --bind mimetype:s:vnd.android.cursor.item/email_v2 --bind "data1:s:'$email'" --bind data2:i:1 >/dev/null
  fi
  echo "added raw_contact $id: $name $phone $email"
}

add_contact "Tania Cohen" "+972 54-123-4567" "tania@example.com"
add_contact "Dana Mizrahi" "+1 555-123-4567" "dana@example.com"
add_contact "יוסי לוי" "+972 52-765-4321" ""
add_contact "Bob Smith" "+1 650-555-0100" "bob@example.com"
