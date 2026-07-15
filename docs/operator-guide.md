# Operator Guide

## First Deployment
1. Register quality engineers, plants, cable-run batches, personnel and robots.
2. Import historical batch / end-of-line / harness-standard-rules records.
3. Run read-only validation and robot tensile-pull-test-cell mission dry-runs.
4. Configure harness-standard evidence checklists and human sign-off paths.
5. Publish a dry-run audit export.

## Minimum Production Controls
- governor gate on every robot action before dispatch
- human sign-off for `:high`/`:safety-critical` robot actions (e.g. shipment of a cable-run batch, harness-certificate issuance)
- audit export for every shipment, sign-off and disclosure
- backup manual process

## Certification
Certified operators must prove robot-safety integrity, evidence-backed
records and human review for safety-affecting actions.

## Operating states
intake : harness-standard-rules-verify : end-of-line-quality-screen : robotics-simulate-tensile-pull-test : approve : ship-cable-run-batch : issue-harness-certificate : audit

## Real physics simulation

`:robotics/simulate-tensile-pull-test` runs an actual time-stepped
`physics-2d` rigid-body simulation of the conductor/crimp tensile-pull
test (not a symbolic pass/fail) -- a moving pull-clamp body travels at
a controlled crosshead rate across the cable's compliant stretch
distance, then collides with a static limit-boundary body representing
the point of conductor/crimp failure. The peak simulated deceleration,
times the batch's own recorded effective participating mass, yields a
real `:sim-peak-pull-force-n` reading the governor independently
rechecks against a disclosed 60 N floor (see `harnessworks.robotics`
ns docstring for the full engineering-prior disclosure and confidence
level).

## Audit export (social operation)

After a production session, export the append-only package for
harness-standard inspectors or internal compliance:

```clojure
(require '[harnessworks.store :as store]
         '[harnessworks.export :as export])
(export/audit-package store)        ; EDN maps
(export/package->csv-bundle store)  ; CSV files as string map
```

Drafts remain **unsigned** — signing and submission to an OEM/
inspector are the wire/cable/harness manufacturer's own acts (see
README Actuation honesty).

Static UI sample: `docs/samples/operator-console.html`.
