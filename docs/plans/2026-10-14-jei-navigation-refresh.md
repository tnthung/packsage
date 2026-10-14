# JEI navigation refresh follow-up

Date: 2026-10-14.

The shared-reader acceptance checks found a JEI navigation failure in Evolution and ATM10. A fresh exact GET cannot rediscover an emitted viewer handle because its capture version changes. This follows the earlier [historical navigation specification](../specs/2026-10-14-historical-navigation.md); the inventory refactor did not change this identity path.

## Scope and constraints

- Preserve generic navigation's exact provider/source/destination validation and request-scoped model evidence.
- In the active JEI runtime, retain bounded native registrations for emitted handles. Revalidate the same registered recipe/category and freshly project its current layout, ingredients and visibility under the original exact handle.
- Reject unretained handles, unavailable runtime, removed/replaced registrations and unavailable destinations. Do not guess a destination from an ordinal, parse an old handle into a fuzzy lookup, or present a historical projection as fresh evidence.
- Keep runtime/version ownership and current scope checks. This does not add a cross-restart stable recipe identifier.
- Commit written implementation/tests before running checks. Corrections found by tests belong in separate commits. No formatters.

## Checks

- [ ] Add a regression covering an exact GET after capture versions advance, plus rejection when the original registration is removed or replaced.
- [ ] Write and commit the bounded JEI correction; run focused tests and the full test/build suite, correcting failures in separate commits.
- [ ] Install a verified JAR with clients closed; check actual JEI navigation and record the tested pack versions and limitations.
- [ ] Update validation/changelog and run relative-link and diff checks.
