# 20261024-1415-settings-e2e-flake Fix occasional SettingsE2eTest failure

- **status**: completed
- **priority**: P2
- **owner**: bkd-l1/eynzhrra
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

- complete: 2026-10-24: done in bkd/b3kns03j, merged into main as 99a9f68; gate and full device suite green on main.
