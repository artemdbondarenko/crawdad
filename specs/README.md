# Specifications

This directory describes what Crawdad does: its observable behaviour, from the point of view of the
person using it and of the clients talking to the service.

- **Specification first.** Requirements are agreed before code is written. The specification here
  is updated in the same pull request as the code that implements it, so on `main` the two always
  match. When they disagree, the specification wins and the code is fixed.
- **What goes here:** behaviour and contracts — user scenarios, the messages between the service
  and its clients, the rules the service enforces.
- **What does not:** how things are built and why a design was chosen. Those are architecture
  decisions, recorded in [`docs/adr/`](../docs/adr/).

The directory is empty until the first feature is specified.
