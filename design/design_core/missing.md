# Missing / Unimplemented Rules

Rules present in `extraction/extracted_text.txt` that are not reflected in any design document.

---

## 1. Host Rating Random Generation Table (rules p. 205) ✓ resolved

**Fix:** Documented in `creation.md` (Host Rating Random Generation section).

---

## 2. Trigger Step Random Generation (rules p. 211) ✓ resolved

**Fix:** Documented in `creation.md` (Security Sheaf Random Generation section).

---

## 3. Host/Grid Reset Mechanics (rules p. 212) ✓ resolved

**Fix:** Documented in `movement.md` (System Reset Mechanics section).

---

## 4. LTG Failed-Logon Tally Memory (rules p. 218) ✓ resolved

**Fix:** Documented in `movement.md` (`logonToLtg` method, LTG failed-logon tally memory window paragraph).

---

## 5. Physical Enhancements Don't Affect Matrix Initiative (rules p. 223) ✓ resolved

The rules explicitly state: *"Wired reflexes, magical augmentation, vehicle-control rigs, and other enhancements that increase the Reaction Attribute of a decker's physical body do not affect Initiative in the Matrix."*

`combat.md` designs `rollDeckerInitiative` using `decker.persona!!.reaction` (persona Reaction, not physical Reaction), which implies the rule, but the constraint is never stated or enforced explicitly.

**Where it belongs:** `combat.md` (Initiative section).

---

## 6. Meatworld Comms — Action Timing Penalty (rules p. 222–223) ✓ resolved

Beyond the initiative die penalty already covered in `combat.md`, the rules add a second rule: *"Deckers who are communicating directly by voice or datascreen with the meatworld resolve their actions along with the physical actions of an Initiative Pass as well, even if they have actions available before that time."*

This means a comms-engaged decker not only rolls fewer initiative dice but also loses their place in the Matrix turn order. The exception is hitcher-electrode comms or communications with other personas on the same system.

`combat.md` handles only the `-1D6 initiative` aspect; it does not model the action-timing displacement.

**Where it belongs:** `combat.md` (Cybercombat Sequence section).

---

## 7. Delayed Action Resolution with Physical World (rules p. 222) ✓ resolved

The rules state: *"If a decker declares a Delayed Action to wait for something to happen in the physical world, resolve his action along with any physical actions of the Initiative Pass."* (And Matrix actions still precede physical actions within the same pass, so a decker delaying for a physical event goes after IC that acts in that pass.)

`combat.md` mentions delayed actions exist but does not specify this meatworld-sync ordering rule.

**Where it belongs:** `combat.md` (Cybercombat Sequence section).

---

## 8. Evade Detection — IC Re-Detection Timing (rules p. 224–225)

The rules specify: *"IC programs re-detect evading icons in a number of Combat Turns equal to the net successes of the icon's Evasion Test. This time is shortened by 1 turn for each point added to the icon's security tally during the period."*

`combat.md` returns `ManeuverResult.Success(netSuccesses)` but does not design the re-detection countdown or the tally-shortening mechanic.

**Where it belongs:** `combat.md` (Combat Maneuvers section).

---

## 9. Black IC — Data Deletion on MPCP Destruction (rules p. 230) ✓ resolved

When lethal black IC kills the decker, it makes a final blaster-equivalent attack on the MPCP at **double its rating**. If this completely destroys the MPCP (rating reduced to 0), the IC also:
- Deletes all data downloaded by the decker during the run.
- Deletes any such data stored in connected storage memory.
- Reduces the MPCP Rating to 0 explicitly.

`combat.md` calls `resolveBlasterMpcpTest` at `ic.rating * 2` but does not model the conditional data-deletion or the explicit MPCP-to-zero outcome.

**Where it belongs:** `combat.md` (`resolveLethalBlackIc` section).

---

## 10. Non-Lethal Black IC — Final MPCP Shot on Unconsciousness (rules p. 230) ✓ resolved

The rules state: *"the non-lethal black IC still gets a final shot at the cyberdeck's MPCP and the data downloaded during the run"* when the decker is rendered unconscious.

`combat.md`'s `resolveNonLethalBlackIc` does not include this final MPCP attack step.

**Where it belongs:** `combat.md` (`resolveNonLethalBlackIc` section).

---

## 11. Scramble IC — Data Destruction on Failed Decrypt (rules p. 228) ✓ resolved

The rules specify: *"If the decker tries to decrypt scramble IC and fails, the gamemaster makes a Scramble Test using its Rating against a target number equal to the decker's Computer Skill. If the test fails [i.e., the Scramble Test fails], the decker has managed to suppress the scramble IC's destruct code. If the test succeeds, the data is destroyed."*

`operations.md` designs the `Decrypt Access/File/Slave` operations but does not model the follow-up Scramble destruct test on a failed decrypt attempt.

**Where it belongs:** `operations.md` (Decrypt operations section) or `combat.md` (Scramble IC section).

---

## 12. Buffered Messages (rules p. 224) ✓ resolved

The rules describe a Free Action to buffer a message: *"the decker may write a message up to 100 words long and give it to any character linked to the decker with hitcher electrodes, radiolink, datascreen, or other device. The second character may also operate an icon the decker can 'see.' The second character receives the buffered message at the end of the Combat Turn."*

No design document covers this mechanic.

**Where it belongs:** `operations.md` (Free Actions section) or `cyberdeck_and_program_mechanics.md` (Accessories section).

---

## 13. Deckers Cannot Suppress IC After Leaving a System (rules p. 212) ✓ resolved

The rules state: *"Deckers cannot suppress IC in a system they have left."*

`combat.md` states the `suppressIc()` precondition as "may only be called in the same action that crashed the IC" but does not explicitly enforce the cannot-suppress-after-leaving rule.

**Where it belongs:** `combat.md` (IC Suppression section).

---

## 15. ICC-10 — Companion Plug-Pull While Black IC is Active

PRD ICC-10: *"If a companion at the jackpoint manually pulls the plug while Black IC is active, Black IC also gets one automatic final attack."*

`combat.md`'s `resolveJackOutWithPin` only models the decker's own Willpower test to jack out. The scenario where a third party physically severs the connection at the jackpoint is not designed: it is unclear whether a Willpower test is skipped, whether the same final attack is resolved, and who calls `resolveJackOutWithPin` (or a variant) in that case.

**Where it belongs:** `combat.md` (Black IC Pin section) and `movement.md` (`jackOut` section).

---

## 14. Legitimate Passcode — Devalidation on Jack-Out (rules p. 226) ✓ resolved

The rules state that if a decker uses an acquired or planted Legitimate passcode to gain Legitimate combat status against the host's own IC, *"the host devalidates the passcode when the decker jacks out or logs off. He has blown his cover."* However, the decker may use the passcode in combat against *other* intruding deckers without blowing cover.

`combat.md` models `PersonaStatus` (Legitimate/Intruding) and `cyberdeck_and_program_mechanics.md` notes the Analyze Host result can distinguish them, but no design document models the devalidation mechanic or the cover-blown condition.

**Where it belongs:** `movement.md` (`jackOut`/`gracefulLogoff` section) or `combat.md`.
