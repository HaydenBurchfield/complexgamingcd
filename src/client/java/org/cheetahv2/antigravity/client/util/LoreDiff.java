package org.cheetahv2.antigravity.client.util;

import java.util.ArrayList;
import java.util.List;

/**
 * LoreDiff — turns "current lore" + "lore I want" into the command list.
 *
 * Only two commands are available on this server:
 *   /sll <n> <text>  edits line n — and n == size+1 APPENDS a new line
 *   /rll <n>         deletes line n; everything below shifts up
 * (addll / ill are permission-denied.)
 *
 * So the script is:
 *   1. delete surplus lines, highest index first — a higher removal never
 *      disturbs a lower index, so nothing shifts mid-script
 *   2. walk the wanted lines in ASCENDING order, writing the ones that differ.
 *      Ascending matters: each append grows the lore by one, so the next
 *      append is always exactly size+1 and never overshoots (writing past
 *      that is the "that line number does not exist" error).
 *
 * Inserting in the middle therefore costs a rewrite of everything below it —
 * the lines shift down instead of being overwritten.
 */
public final class LoreDiff {

    private LoreDiff() {}

    public static List<String> commands(List<String> current, List<String> desired) {
        List<String> out = new ArrayList<>();
        if (current == null) current = new ArrayList<>();
        if (desired == null) desired = new ArrayList<>();

        // 1. surplus removals, highest index first
        for (int i = current.size(); i > desired.size(); i--) {
            out.add(LoreCommandConfig.removeLine(i));
        }

        // 2. writes in ascending order (edit in place, or append at size+1)
        int overlap = Math.min(current.size(), desired.size());
        for (int i = 0; i < desired.size(); i++) {
            String want = desired.get(i) == null ? "" : desired.get(i);
            if (i < overlap && current.get(i).equals(want)) continue; // already correct
            out.add(LoreCommandConfig.setLine(i + 1, want));
        }
        return out;
    }
}
