# Patch 0.14.0 — Campaign Confirmation Snapshot

## Purpose

Turn an editable saved message draft into an immutable campaign snapshot before any sending workflow exists.

## Main changes

- activate Campaigns as a confirmed-campaign list
- add Review & Confirm flow from saved composer drafts
- freeze recipients and rendered personalized messages
- server-side personalization rendering during confirmation
- server-side GSM-7 / Unicode segment recalculation
- freeze total estimated SMS units
- freeze consent/suppression evidence from the selected eligibility snapshot
- freeze paired Android phone + explicitly selected SIM/subscription
- add confirmed campaign detail screen
- add recipient snapshot preview
- keep sending / scheduling / queueing disabled

## New migration

```text
20261001000300_campaign_confirmation_snapshot.sql
```

## Database objects

```text
campaigns
campaign_recipients
confirm_campaign(...)
list_campaign_confirmations(...)
get_campaign_confirmation(...)
list_campaign_confirmation_recipients(...)
```

## Apply

```bash
./apply.sh /home/noman/projects/bulktext-web-0.4.0
```

Then:

```bash
cd /home/noman/projects/bulktext-web-0.4.0
nvm use || nvm install
npm install
npm run validate
npm audit
npx supabase migration list
npx supabase db push --dry-run
```

Dry-run must show only:

```text
20261001000300_campaign_confirmation_snapshot.sql
```

After inspection:

```bash
npx supabase db push
npx supabase db lint --linked
```

## Out of scope

- actual SMS sending
- gateway readiness authorization
- queue jobs / leases
- scheduling
- retry execution
- delivery reporting
