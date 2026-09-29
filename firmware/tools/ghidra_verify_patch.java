// Disassemble a window around the watchdog HDMI-check to verify a patch.
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.address.Address;

public class ghidra_verify_patch extends GhidraScript {
    @Override
    public void run() throws Exception {
        long start = 0x08035AF8L, end = 0x08035B20L;
        // force-disassemble the range as thumb
        disassemble(toAddr(start));
        Address a = toAddr(start);
        InstructionIterator it = currentProgram.getListing().getInstructions(a, true);
        println("--- verify disasm around patch site ---");
        while (it.hasNext()) {
            Instruction ins = it.next();
            long off = ins.getAddress().getOffset();
            if (off >= end) break;
            byte[] b = ins.getBytes();
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x & 0xff));
            println(String.format("  0x%08X: %-12s  %s", off, sb.toString(), ins.toString()));
        }
    }
}
