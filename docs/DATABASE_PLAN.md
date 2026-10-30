# Database integration plan (Supabase)

**Status: not connected.** Sada currently stores everything in the browser's localStorage on one device. There is no account, no sync, and no server copy. Nothing in the app should say otherwise until every step below is done and tested.

## Why it's not wired in yet

Sync is only safe once three things work together: real authentication, row-level security that's been tested with two accounts, and a first upload that can't lose or duplicate words. Shipping half of that is worse than device-only storage.

## Steps

### 1. Authentication
- Supabase Auth with email magic link (works on a phone, no password to forget). Google sign-in can come later.
- The app stays fully usable signed out. Signing in is offered from Settings, never forced.
- Only the public `anon` key goes in the frontend (`VITE_SUPABASE_URL`, `VITE_SUPABASE_ANON_KEY`). The service-role key is never used in the browser.

### 2. Schema and security
- Run `supabase/schema.sql`.
- Test RLS with two test accounts: account B must get zero rows from every table for account A's data, including via direct REST calls with the anon key.
- No public policy on `words`. Stage 3 adds a view exposing only `word` and a display name for words marked public.

### 3. First upload (local → account), without data loss
1. Before anything is sent, keep a full copy of local data under `sada:v1:pre-sync-backup` (the store already parks unreadable data and pre-migration copies; this adds one more).
2. Upload with `upsert` on `(user_id, local_id)` for words and `(word_id, local_key)` for contexts. Re-running the upload changes nothing, so a dropped connection halfway through is safe to retry.
3. Read the server rows back and compare counts with local. Only then mark the device as synced (`syncedUserId` in the local store).
4. Local data is never deleted by sync. If the upload fails, the app keeps working from local storage exactly as now.

### 4. Ongoing sync
- Local-first: every change is written locally immediately (as now), then queued for upload.
- Each row carries `updated_at`; on conflict the newer change wins. Review history (`reviews`) is append-only, so it never conflicts.
- Deletes are soft (`deleted_at`) so another device learns the word was removed rather than re-uploading it.
- A second device signing in downloads the account's words and merges them with any local words using the same upsert keys.

### 5. Signing out / switching accounts
- Signing out leaves local data on the device (it's the learner's). Signing in as a different account asks whether to upload local words to that account, keep them separate, or remove them from this device.

## Definition of done
- Two real accounts on two devices: a word saved on device 1 appears on device 2 after refresh; deleting it on device 2 removes it on device 1.
- RLS test above passes.
- Killing the connection during first upload and retrying produces no duplicates and no loss.
- Only then does the README describe Sada as syncing across devices.
