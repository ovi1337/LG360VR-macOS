import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;

public class ghidra_callees extends GhidraScript {
    DecompInterface dec;
    void dc(long addr){
        Function fn=getFunctionContaining(toAddr(addr));
        if(fn==null){ disassemble(toAddr(addr)); createFunction(toAddr(addr),null); fn=getFunctionContaining(toAddr(addr)); }
        if(fn==null){ println("no fn @ 0x"+Long.toHexString(addr)); return; }
        println(String.format("\n/* ==== %s @ 0x%08X ==== */", fn.getName(), fn.getEntryPoint().getOffset()));
        DecompileResults r=dec.decompileFunction(fn,60,monitor);
        if(r!=null&&r.decompileCompleted()) println(r.getDecompiledFunction().getC());
        else println("<fail>");
    }
    @Override public void run(){
        dec=new DecompInterface(); dec.openProgram(currentProgram);
        dc(0x0802f556L);  // final call in GoToDload -> likely the reset/dload trigger
        dc(0x0802d9e8L);  // preceding call
        dc(0x08034a64L);  // method on object
        println("########## END ##########");
    }
}
