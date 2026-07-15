# Governance

`cloud-itonami-isic-2732` is an OSS open-business blueprint for manufacture
of other electronic and electric wires and cables (ISIC Rev.5 2732) --
automotive wiring-harness and fine-gauge electronics flex-cable assembly,
robotics-premised.

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a robot action the governor refuses is never dispatched to hardware.
- the Cable-Integrity Governor remains independent of the Harness Advisor.
- hard policy violations (force-shipment, record-suppression, unauthorized
  disclosure) cannot be overridden by human approval.
- every shipment, certificate issuance, sign-off, record and disclose path
  is auditable.
- sensitive operating and personal data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or license
should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, robot-safety, audit and
data-flow review.

Certified operators can lose certification for:
- bypassing robot-safety or record policy checks
- mishandling sensitive data
- misrepresenting certification status
- failing to respond to security or safety incidents
