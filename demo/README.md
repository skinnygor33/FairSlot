# Demo scripts

The five scenarios of the final demonstration (proposal §6). Each one is a single Java
file that talks to the REST API like real browsers would, prints what happened and ends
with **PASS** (exit code 0) or **FAIL** (exit code 1). Only a JDK 17+ is needed:

```sh
java demo/Demo1Contention.java [baseUrl] [clients] [resourceId] [date]
java demo/Demo2Quota.java [baseUrl] [user] [sessions] [requestsPerSession] [date] [--keep]
```

| # | Scenario | Script | Status |
|---|---|---|---|
| 1 | Contention: many students, one slot | `Demo1Contention.java` | ready |
| 2 | Quota: one student, many sessions | `Demo2Quota.java` | ready |
| 3 | Waitlist and no-show: live offer in the browser | — | week 4 (Roger); works by hand in the UI already |
| 4 | Bundle booking: room + projector on two nodes | — | week 4 (Roger) |
| 5 | Failure: kill a node, including the coordinator mid-bundle | — | week 4 |

Defaults target `http://localhost:8080` and tomorrow's date, using the demo accounts
(`student01`..`student50`, password `fairslot`). On the cloud VMs, pass the web VM's URL
as the first argument.

Example run against the dev server (mock back-end), 23 Nov 2026:

```
Demo 1 - contention: 30 students, room:B1-101 on 2026-11-24
30 simultaneous requests answered in 10 ms:
  hold granted     1
  slot_taken      29
Winner student01 confirms: booked b-9
29 students joined the waitlist, positions 1..29: OK
PASS: exactly one booking, everyone else on the waitlist.

Demo 2 - quota: student50 from 3 sessions x 6 requests on 2026-11-24
Quota before: 0/10 hours
Results of 18 simultaneous requests:
  hold granted     10
  quota_exceeded    8
Quota after: 10/10 hours
PASS: the weekly quota was never exceeded.
```
