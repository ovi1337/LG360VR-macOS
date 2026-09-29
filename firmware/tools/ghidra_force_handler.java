// Force-disassemble the GoToDload handler @ 0x0803747C, dump raw bytes + instructions + decompile.
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;

public class ghidra_force_handler extends GhidraScript {
    @Override public void run() throws Exception {
        long H = 0x0803747CL;
        Address a = toAddr(H);
        // raw bytes
        StringBuilder sb = new StringBuilder("bytes @0x"+Long.toHexString(H)+": ");
        for (int i=0;i<48;i++){ sb.append(String.format("%02x ", getByte(a.add(i))&0xff)); }
        println(sb.toString());
        // clear whatever is there and force code
        try { clearListing(a, a.add(120)); } catch(Exception e){}
        disassemble(a);
        Function fn = getFunctionContaining(a);
        if (fn==null){ createFunction(a,"do_GoToDload_h"); fn=getFunctionContaining(a); }
        Listing lst = currentProgram.getListing();
        InstructionIterator it = lst.getInstructions(a, true);
        int n=0;
        println("---- disasm ----");
        while(it.hasNext() && n<50){
            Instruction ins=it.next();
            byte[] b=ins.getBytes(); StringBuilder h=new StringBuilder();
            for(byte x:b) h.append(String.format("%02x",x&0xff));
            println(String.format("0x%08X  %-12s  %s", ins.getAddress().getOffset(), h.toString(), ins.toString()));
            n++;
        }
        if(fn!=null){
            DecompInterface dec=new DecompInterface(); dec.openProgram(currentProgram);
            DecompileResults r=dec.decompileFunction(fn,60,monitor);
            if(r!=null && r.decompileCompleted()){ println("---- decompile ----"); println(r.getDecompiledFunction().getC()); }
        }
        println("########## END ##########");
    }
}
