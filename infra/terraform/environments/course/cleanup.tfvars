# Overlay for the explicit course teardown. Apply with course.tfvars first, then
# this file, then destroy. This turns on force_destroy, turns off deletion
# protection and the final snapshot, and sets the secret recovery window to zero.
# The destroy needs explicit human approval of the target list (cleanup.sh).

cleanup_mode = true
