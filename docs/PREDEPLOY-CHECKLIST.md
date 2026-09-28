# Loupe on your iPhone: the pre-deploy checklist

What the simulator cannot test. Everything else has an automated scenario (`ios/LoupeUITests/Scenarios/`). Use a
Debug build installed with `ios/scripts/device-build.sh`. Tick each line, and note anything that looks wrong with a
screenshot. About 45 minutes, plus the model download and one night on the charger.

**Before you start:** your iPhone is charged, on Wi-Fi, and has at least 1 GB free.

## 1. The decision model (a real download)
- [ ] Me → Decision model → **Download**. The consent sheet says about 418 MB, once, and only the model goes online. Agree.
- [ ] Progress moves. **Pause**, then **Resume**. Leave Loupe for a minute: it keeps going.
- [ ] When it finishes: close Loupe fully (swipe it away), open it again. It goes **straight to the tabs**. Get the decision model does not come back.
- [ ] Me → Diagnostics → 3 passes → Run. Share the report back.

## 2. Loupe for Safari
- [ ] Guard → Protection → **Turn on Safari protection**. In Settings turn on **Allow Extension**, then **All Websites → Allow**.
- [ ] In Safari open `xn--pypal-4ve.com`. Loupe's full-page warning shows with its reasons. **Go back** works.
- [ ] Open it again and tap **Continue anyway**: the page loads, and no second warning appears in that Safari session.
- [ ] Open an ordinary site (bbc.co.uk): no Loupe UI at all.
- [ ] Back in Loupe: Guard shows a badge, and **Spotted** lists the look-alike site. Opening Spotted clears the badge.

## 3. The Loupe keyboard (Full Access and memory)
- [ ] Guard → Protection → Clipboard → **Set up** → **Add the Loupe keyboard**. Turn on Loupe, then **Allow Full Access**.
- [ ] In Notes, hold the globe key and pick Loupe. Type English and Arabic (ع / EN key). Holding ا shows أ إ آ.
- [ ] Copy a link. The strip says "Copied a link · tap to check it". Tap it and answer **Allow Paste**. The verdict shows in the strip.
- [ ] Copy `xn--pypal-4ve.com`: the strip warns about a fake PayPal page.
- [ ] Type fast for a minute. The keyboard never stalls or disappears; iOS kills a keyboard over its memory limit.
- [ ] Back in Loupe: the Clipboard card says **Full Access on** and shows the keyboard's peak memory.

## 4. Clipboard, Siri, the widget
- [ ] Copy a link in Messages, then open Loupe. The chip **"Check the link you copied?"** shows. **Check** asks Allow Paste, then the banner answers.
- [ ] Set Settings → Apps → Loupe → **Paste from Other Apps → Allow**. Check again: no question this time.
- [ ] Say **"Check what I copied with Loupe"**. Siri answers in one sentence.
- [ ] Add the **Check copied** widget to the Home Screen and the Lock Screen. Tap it: Loupe opens and checks at once.

## 5. Notifications
- [ ] Allow notifications when asked (onboarding, or on turning Sort while charging on).
- [ ] Visit a look-alike site in Safari with Safari protection on: a Loupe notification arrives, at most once an hour for that site.
- [ ] After section 6: a notification with the real counts ("… sorted, … need you").

## 6. Sorting while charging (overnight)
- [ ] Me → **Sort while charging** is on. Plug in, lock the phone, and leave it overnight.
- [ ] In the morning: Me → Sort while charging shows the last run with real counts. Did the phone get hot? Note the battery.
- [ ] Me → **Run now** also works in the foreground, and **Cancel** stops it.

## 7. The game with two thumbs
- [ ] Me → See Loupe think → **Play**. Steer with one thumb on the river and hold **FIRE** with the other at the same time. Both work together: steering never fires, and FIRE never pauses.
- [ ] A quick tap on the river pauses. FIRE is never over the river or the plane.
- [ ] **Watch Loupe** with the model installed: the speed panel shows decisions per second. When the run ends, the **results card** shows (the simulator cannot show this; it needs the model).

## 8. A real Gmail sign-in
- [ ] Sources → Mail → **Sign in with Google**, with your test-user account. The consent screen asks for **read-only** mail.
- [ ] Mail fills in with "Online · gmail · fetched …". Nothing is marked read in Gmail.
- [ ] Me → Mail lists your mail, with what was found (phishing, needs a reply, subscriptions). Close and reopen Loupe: still signed in. **Remove mailbox** asks first, then removes the messages.

## 9. Phone sources with real data
- [ ] Sources → Photos → Allow access (try **Limited** first, then **Full**). The live scan shows your thumbnails and settles on a summary.
- [ ] Files → **Add files or a folder** → pick a folder from iCloud Drive. Its files are read. Close and reopen Loupe: the folder is still there.
- [ ] From Files or Photos, share a PDF with **Send to Loupe**. It appears on the next open. Share a link: the share sheet shows the link's verdict.
- [ ] A privacy finding → **Open original** opens the photo or document itself.

## 10. The device smoke run (only if you say so)
The non-destructive automated subset for this iPhone is the **`LoupeDeviceSmoke`** scheme. It changes one game setting and puts it back, and adds one link check to Recent checks. It never deletes anything.
`cd ios && xcodebuild test -project Loupe.xcodeproj -scheme LoupeDeviceSmoke -destination 'platform=iOS,id=<your UDID>'`

## 11. The three places (Home · Ask · Me)
- [ ] Loupe opens on **Home**. Needs attention shows only when something needs you; Quick check, Money, Documents and Protected follow, each opening its screen.
- [ ] Opening Loupe starts no scan: Home shows the latest results, or Not checked yet with Run now.
- [ ] Tap **Home** again on a pushed screen: back to Home's first screen. The same for Ask and Me.
- [ ] **Ask** shows a number when answers are waiting; "Needs you" opens the queue.
- [ ] **Me → What Loupe reads** lists every source; **Mail** is one entry and one screen (mailbox, found, actions).
- [ ] Settings → Accessibility → Larger Text at the largest size, and the phone in Arabic: every button on Home, Ask and Me is still tappable and readable.
