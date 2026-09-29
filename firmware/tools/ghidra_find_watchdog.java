// Ghidra headless post-script (Java): find xrefs to LG360VR watchdog strings
// and decompile the referencing functions.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.symbol.Reference;
import ghidra.util.task.ConsoleTaskMonitor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

public class ghidra_find_watchdog extends GhidraScript {
    @Override
    public void run() throws Exception {
        Object[][] targets = {
            {"Waiting for HDMI (Init_Reg)", 0x0803602CL},
            {"stop to wait HDMI",           0x0803606CL},
            {"HDMI signals arrived",        0x08036094L},
            {"BSP_TC358870XBG_Start_Video", 0x08036010L},
            {"ANX7401_power_on",            0x08038DC8L},
            {"do_VRAppStart",               0x080387B0L},
            {"do_GoToDload",                0x080374B4L},
        };
        FunctionManager fm = currentProgram.getFunctionManager();
        Map<Long, Function> toDecompile = new TreeMap<>();

        println("\n########## XREF REPORT ##########");
        for (Object[] t : targets) {
            String name = (String) t[0];
            long addr = (Long) t[1];
            Address a = toAddr(addr);
            Reference[] refs = getReferencesTo(a);
            println(String.format("\n=== [%s] @ 0x%08X : %d refs ===", name, addr, refs.length));
            for (Reference r : refs) {
                Address fr = r.getFromAddress();
                Function fn = fm.getFunctionContaining(fr);
                if (fn != null) {
                    long entry = fn.getEntryPoint().getOffset();
                    println(String.format("   ref from 0x%08X  in FUNC %s @ 0x%08X (%s)",
                            fr.getOffset(), fn.getName(), entry, r.getReferenceType()));
                    if (name.startsWith("Waiting") || name.startsWith("stop to wait")
                            || name.startsWith("HDMI signals") || name.startsWith("BSP_TC358870XBG_Start")) {
                        toDecompile.put(entry, fn);
                    }
                } else {
                    println(String.format("   ref from 0x%08X  (no function)", fr.getOffset()));
                }
            }
        }

        println("\n########## DECOMPILATION OF WATCHDOG/VIDEO FUNCTIONS ##########");
        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);
        ConsoleTaskMonitor mon = new ConsoleTaskMonitor();
        for (Map.Entry<Long, Function> e : toDecompile.entrySet()) {
            Function fn = e.getValue();
            println(String.format("\n/* ============ %s @ 0x%08X ============ */",
                    fn.getName(), e.getKey()));
            DecompileResults res = dec.decompileFunction(fn, 60, mon);
            if (res != null && res.decompileCompleted()) {
                println(res.getDecompiledFunction().getC());
            } else {
                println("   <decompile failed>");
            }
        }
        println("\n########## END ##########");
    }
}
