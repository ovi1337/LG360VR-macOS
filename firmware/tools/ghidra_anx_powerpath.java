import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.mem.*;
import java.util.*;

public class ghidra_anx_powerpath extends GhidraScript {
    DecompInterface dec;

    Address findStr(String s) {
        try {
            Address[] hits = findBytes(currentProgram.getMinAddress(), s, 1);
            return (hits == null || hits.length == 0) ? null : hits[0];
        } catch (Exception e) { return null; }
    }

    java.util.List<Address> findPtrs(long target) {
        java.util.List<Address> out = new java.util.ArrayList<>();
        byte[] pat = new byte[]{
            (byte)(target & 0xFF), (byte)((target>>8)&0xFF),
            (byte)((target>>16)&0xFF), (byte)((target>>24)&0xFF)};
        Memory mem = currentProgram.getMemory();
        Address a = currentProgram.getMinAddress();
        while (a != null) {
            Address hit = mem.findBytes(a, pat, null, true, monitor);
            if (hit == null) break;
            out.add(hit);
            a = hit.add(1);
        }
        return out;
    }

    Function fnRefingStr(String label, String s) {
        Address sa = findStr(s);
        if (sa == null) { println(label + ": string not found"); return null; }
        println(String.format("%-22s string @ 0x%08X", label, sa.getOffset()));
        Function found = null;
        // 1) direct references
        Reference[] refs = getReferencesTo(sa);
        for (Reference r : refs) {
            Function f = getFunctionContaining(r.getFromAddress());
            if (f != null) { println("   directref fn=" + f.getName()+"@0x"+Long.toHexString(f.getEntryPoint().getOffset())); if(found==null) found=f; }
        }
        // 2) literal-pool pointers to the string, then refs to those literals
        for (Address lit : findPtrs(sa.getOffset())) {
            Reference[] lrefs = getReferencesTo(lit);
            for (Reference r : lrefs) {
                Function f = getFunctionContaining(r.getFromAddress());
                if (f == null) { disassemble(r.getFromAddress()); f = getFunctionContaining(r.getFromAddress()); }
                println(String.format("   litptr@0x%08X <- code@0x%08X fn=%s", lit.getOffset(),
                        r.getFromAddress().getOffset(), f==null?"<none>":f.getName()+"@0x"+Long.toHexString(f.getEntryPoint().getOffset())));
                if (f != null && found == null) found = f;
            }
        }
        return found;
    }

    void decompShort(Function fn, int maxLines) {
        if (fn == null) return;
        DecompileResults r = dec.decompileFunction(fn, 60, monitor);
        println(String.format("\n/* ==== %s @ 0x%08X ==== */", fn.getName(), fn.getEntryPoint().getOffset()));
        if (r != null && r.decompileCompleted()) {
            String c = r.getDecompiledFunction().getC();
            String[] ls = c.split("\n");
            for (int i = 0; i < Math.min(ls.length, maxLines); i++) println(ls[i]);
            if (ls.length > maxLines) println("... [" + (ls.length - maxLines) + " more lines]");
        } else println("<decompile fail>");
    }

    void listCalls(Function fn) {
        if (fn == null) return;
        Set<Function> called = fn.getCalledFunctions(monitor);
        println("  " + fn.getName() + " calls: " + called.size() + " fns");
    }

    @Override public void run() {
        dec = new DecompInterface();
        dec.openProgram(currentProgram);

        println("########## ANX power-path analysis ##########");
        Function anxPower = fnRefingStr("ANX7401_power_on", "ANX7401_power_on");
        Function anxInit  = fnRefingStr("ANX7401_Init",     "ANX7401_Init");
        Function bspAnx   = fnRefingStr("BSP_ANX7737_On",   "BSP_ANX7737_On Init");
        Function vrStart  = fnRefingStr("do_VRAppStart",    "do_VRAppStart");
        Function bspInit  = fnRefingStr("BSP_Init",         "Starting BSP Init");

        println("\n===== decompile do_VRAppStart (who powers ANX?) =====");
        decompShort(vrStart, 80);

        println("\n===== decompile ANX7401_power_on =====");
        decompShort(anxPower, 60);

        println("\n===== decompile BSP_Init (boot path) =====");
        decompShort(bspInit, 100);

        println("\n===== call summaries =====");
        listCalls(vrStart); listCalls(bspInit); listCalls(anxPower);

        // Does BSP_Init (boot) reach ANX7401_power_on?
        if (bspInit != null && anxPower != null) {
            Set<Function> bc = bspInit.getCalledFunctions(monitor);
            println("BSP_Init directly calls ANX7401_power_on? " + bc.contains(anxPower));
        }
        if (vrStart != null && anxPower != null) {
            Set<Function> vc = vrStart.getCalledFunctions(monitor);
            println("do_VRAppStart directly calls ANX7401_power_on? " + vc.contains(anxPower));
        }
        println("########## END ##########");
    }
}
