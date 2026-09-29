import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;

public class ghidra_disasm_list extends GhidraScript {
    void dump(long start,int count){
        Address a=toAddr(start);
        try{ clearListing(a,a.add(count*4L)); }catch(Exception e){}
        disassemble(a);
        Listing lst=currentProgram.getListing();
        InstructionIterator it=lst.getInstructions(a,true);
        println(String.format("\n---- 0x%08X ----",start));
        int n=0;
        while(it.hasNext()&&n<count){
            Instruction ins=it.next(); StringBuilder h=new StringBuilder();
            try{ for(byte x:ins.getBytes()) h.append(String.format("%02x",x&0xff)); }catch(Exception e){}
            println(String.format("0x%08X  %-12s  %s",ins.getAddress().getOffset(),h.toString(),ins.toString()));
            n++;
        }
    }
    @Override public void run(){
        dump(0x0802f556L,40);
        dump(0x0802d9e8L,30);
        println("########## END ##########");
    }
}
