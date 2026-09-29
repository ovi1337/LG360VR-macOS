// Ghidra headless: dump instruction bytes of the watchdog + decompile helpers,
// and list callers, to craft precise byte patches.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.FunctionManager;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.symbol.Reference;

public class ghidra_dump_watchdog extends GhidraScript {
    private String bytesHex(Instruction ins) throws Exception {
        byte[] b = ins.getBytes();
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    private void disasmFunc(long entry) throws Exception {
        Function fn = getFunctionAt(toAddr(entry));
        if (fn == null) { println("  no func @ "+Long.toHexString(entry)); return; }
        println(String.format("\n--- DISASM %s @ 0x%08X  (body %s) ---",
                fn.getName(), entry, fn.getBody().toString()));
        InstructionIterator it = currentProgram.getListing().getInstructions(fn.getBody(), true);
        while (it.hasNext()) {
            Instruction ins = it.next();
            println(String.format("  0x%08X: %-12s  %s",
                    ins.getAddress().getOffset(), bytesHex(ins), ins.toString()));
        }
    }

    private void decompile(DecompInterface dec, long entry) {
        Function fn = getFunctionAt(toAddr(entry));
        if (fn == null) { println("  no func @ "+Long.toHexString(entry)); return; }
        println(String.format("\n/* ==== %s @ 0x%08X ==== */", fn.getName(), entry));
        DecompileResults res = dec.decompileFunction(fn, 60, monitor);
        if (res != null && res.decompileCompleted())
            println(res.getDecompiledFunction().getC());
        else
            println("  <decompile failed>");
    }

    @Override
    public void run() throws Exception {
        FunctionManager fm = currentProgram.getFunctionManager();

        // callers of the watchdog
        Address wd = toAddr(0x08035A42L);
        println("########## CALLERS OF WATCHDOG FUN_08035a42 ##########");
        for (Reference r : getReferencesTo(wd)) {
            Function fn = fm.getFunctionContaining(r.getFromAddress());
            println(String.format("  called from 0x%08X in %s",
                    r.getFromAddress().getOffset(),
                    fn != null ? fn.getName()+" @ 0x"+Long.toHexString(fn.getEntryPoint().getOffset()) : "?"));
        }

        println("\n########## FULL DISASM OF WATCHDOG ##########");
        disasmFunc(0x08035A42L);

        println("\n########## DECOMPILE HELPERS ##########");
        DecompInterface dec = new DecompInterface();
        dec.openProgram(currentProgram);
        decompile(dec, 0x08035BA2L); // suspected reset
        decompile(dec, 0x080311D6L); // suspected I2C write (start video)
        decompile(dec, 0x08031196L); // suspected I2C read (status)
        decompile(dec, 0x080305BCL); // suspected delay

        println("\n########## DISASM SUSPECTED RESET FUN_08035ba2 ##########");
        disasmFunc(0x08035BA2L);

        println("\n########## END ##########");
    }
}
