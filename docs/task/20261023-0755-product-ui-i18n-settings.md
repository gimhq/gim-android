# 20261023-0755-product-ui-i18n-settings Product-grade UI, languages, account and settings

- **status**: in_progress
- **priority**: P1
- **owner**: bkd-l1/eynzhrra
- **createdAt**: 2026-10-23 07:55

## Description

Bring the Android UI from demo to a basic product benchmarked against Signal
and Telegram, usable alone by a 12-year-old: plain wording, account
explanation, settings, multiple languages. Plan:
`docs/plan/20261023-0755-product-ui-i18n-settings.md`.

## ActiveForm

Turning the Android demo UI into a product UI

## Dependencies

- **blocked by**: (none)
- **blocks**: (none)

## Notes

(none)

- unclaim: Handed over to the BKD L1 coordinator for gim-android.
- 2026-10-24: campaign l1-eynzhrra-20261023084825 paused by the user. Merged and
  pushed: B 507b0ca, A 3c26383, C a75b01a, D 7d2beb7. Open: E (bkd/hucbnppr,
  four languages merged on its branch, E5 picker/locale checks in progress) and
  F (bkd/jdepq1ie, SDK b86283e vendored, F1-F4 merged on its branch; F5 in
  progress, F6-F8 not started). Crons l1-auto-eynzhrra, l2-dispatch-hucbnppr
  and l2-dispatch-jdepq1ie are paused; resume them when the user resumes.
- 2026-10-24: campaign resumed by the user; crons re-enabled; E and F continue
  from their branches (E: final gate + device E2E; F: F5 device run, then F6-F8).
