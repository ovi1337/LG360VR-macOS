// Analyze the "Go to Dload" command handler and reset/flag mechanism.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.symbol.Reference;
import java.util.LinkedHashMap;
import java.util.Map;

public class ghidra_dload extends GhidraScript {
    private DecompInterface dec;
    private void dc(Function fn) {
        if (fn == null) return;
        println(String.format("\n/* ==== %s @ 0x%08X ==== */",
                fn.getName(), fn.getEntryPoint().getOffset()));
        DecompileResults r = dec.decompileFunction(fn, 60, monitor);
        if (r != null && r.decompileCompleted()) println(r.getDecompiledFunction().getC());
        else println("  <decompile failed>");
    }
    @Override public void run() throws Exception {
        FunctionManager fm = currentProgram.getFunctionManager();
        long[] strs = {0x080374B4L, 0x080374C4L, 0x080374DCL, 0x0803EE6CL, 0x080374C9L};
        Map<Long,Function> funcs = new LinkedHashMap<>();
        println("########## XREFS TO DLOAD STRINGS ##########");
        for (long s : strs) {
            Address a = toAddr(s);
            Reference[] refs = getReferencesTo(a);
            println(String.format("\n[0x%08X] %d refs", s, refs.length));
            for (Reference r : refs) {
                Function fn = fm.getFunctionContaining(r.getFromAddress());
                println(String.format("   from 0x%08X in %s", r.getFromAddress().getOffset(),
                        fn!=null? fn.getName()+" @0x"+Long.toHexString(fn.getEntryPoint().getOffset()):"?"));
                if (fn!=null) funcs.put(fn.getEntryPoint().getOffset(), fn);
            }
        }
        dec = new DecompInterface();
        dec.openProgram(currentProgram);
        println("\n########## DECOMPILE DLOAD-RELATED FUNCTIONS ##########");
        for (Function fn : funcs.values()) dc(fn);
        println("\n########## END ##########");
    }
}
