// Decompile the GoToDload handler and follow its calls (flag set + reset path).
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;

public class ghidra_gotodload extends GhidraScript {
    private DecompInterface dec;
    private void dc(long addr) {
        Function fn = getFunctionContaining(toAddr(addr));
        if (fn == null) { disassemble(toAddr(addr)); fn = getFunctionContaining(toAddr(addr)); }
        if (fn == null) { println("no func @ 0x"+Long.toHexString(addr)); return; }
        println(String.format("\n/* ==== %s @ 0x%08X (asked 0x%08X) ==== */",
                fn.getName(), fn.getEntryPoint().getOffset(), addr));
        DecompileResults r = dec.decompileFunction(fn, 60, monitor);
        if (r != null && r.decompileCompleted()) println(r.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    @Override public void run() throws Exception {
        dec = new DecompInterface();
        dec.openProgram(currentProgram);
        // handler from dispatch table entry (0x08040944 -> 0x0803747D thumb)
        dc(0x0803747CL);
        // also the two GoToDload references seen at 0x080408D8/DC area candidates
        dc(0x0803747DL);
        println("\n########## END ##########");
    }
}
