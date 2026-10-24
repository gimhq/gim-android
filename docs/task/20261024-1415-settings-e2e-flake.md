# 20261024-1415-settings-e2e-flake Fix occasional SettingsE2eTest failure

- **status**: pending
- **priority**: P2
- **owner**: (none)
- **createdAt**: 2026-10-24 14:15

## Description

SettingsE2eTest occasionally fails with "No compose hierarchies found" in a full device run and passes on rerun. Find the race and make the test wait for the activity.

Follow-up from `20261023-0755-product-ui-i18n-settings`.

## ActiveForm

Fixing the SettingsE2eTest flake

## Dependencies

- **blocked by**: (none)
- **blocks**: (none)

## Notes

(none)
