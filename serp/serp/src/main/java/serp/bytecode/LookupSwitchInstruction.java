package serp.bytecode;

import java.io.*;
import java.util.*;

import serp.bytecode.visitor.*;
import serp.util.*;

/**
 * The <code>lookupswitch</code> instruction.
 *
 * @author Abe White
 */
public class LookupSwitchInstruction extends JumpInstruction {
    // case info
    private List _matches = new LinkedList();
    private List _cases = new LinkedList();

    LookupSwitchInstruction(Code owner) {
        super(owner, Constants.LOOKUPSWITCH);
    }

    int getLength() {
        // don't call super.getLength(), cause JumpInstruction will return
        // value assuming this is an 'if' or 'goto' instruction
        int length = 1;

        // make the first byte of the 'default' a multiple of 4 from the
        // start of the method
        int byteIndex = getByteIndex() + 1;
        for (; (byteIndex % 4) != 0; byteIndex++, length++);

        // default, npairs
        length += 8;

        // pairs
        length += (8 * _matches.size());
        return length;
    }

    public int getStackChange() {
        return -1;
    }

    /**
     * Synonymous with {@link #getTarget}.
     */
    public Instruction getDefaultTarget() {
        return getTarget();
    }

    /**
     * Synonymous with {@link #setTarget}.
     */
    public LookupSwitchInstruction setDefaultTarget(Instruction ins) {
        return (LookupSwitchInstruction) setTarget(ins);
    }

    /**
     * Synonymous with {@link #getOffset}.
     */
    public int getDefaultOffset() {
        return getOffset();
    }

    /**
     * Synonymous with {@link #setOffset}.
     */
    public LookupSwitchInstruction setDefaultOffset(int offset) {
        setOffset(offset);
        return this;
    }

    /**
     * Set the match-jumppt pairs for this switch.
     *
     * @return this instruction, for method chaining
     */
    public LookupSwitchInstruction setCases(int[] matches, 
        Instruction[] targets) {
        _matches.clear();
        _cases.clear();
        for (int i = 0; i < matches.length; i++)
            _matches.add(Numbers.valueOf(matches[i]));
        for (int i = 0; i < targets.length; i++) {
            InstructionPtrStrategy next = new InstructionPtrStrategy(this);
            next.setTargetInstruction(targets[i]);
            _cases.add(next);
        }
        invalidateByteIndexes();
        return this;
    }

    public int[] getOffsets() {
        int bi = getByteIndex();
        int[] offsets = new int[_cases.size()];
        for (int i = 0; i < offsets.length; i++)
            offsets[i] = ((InstructionPtrStrategy) _cases.get(i)).getByteIndex()
                - bi;
        return offsets;
    }

    /**
     * Return the values of the case statements for this switch.
     */
    public int[] getMatches() {
        int[] matches = new int[_matches.size()];
        Iterator itr = _matches.iterator();
        for (int i = 0; i < matches.length; i++)
            matches[i] = ((Integer) itr.next()).intValue();
        return matches;
    }

    /**
     * Return the targets of the case statements for this switch.
     */
    public Instruction[] getTargets() {
        Instruction[] result = new Instruction[_cases.size()];
        for (int i = 0; i < result.length; i++)
            result[i] = ((InstructionPtrStrategy) _cases.get(i)).
                getTargetInstruction();
        return result;
    }

    /**
     * Add a case to this switch.
     *
     * @return this instruction, for method chaining
     */
    public LookupSwitchInstruction addCase(int match, Instruction target) {
        _matches.add(Numbers.valueOf(match));
        _cases.add(new InstructionPtrStrategy(this, target));
        invalidateByteIndexes();
        return this;
    }

    private Instruction findJumpPoint(int jumpByteIndex, List inss) {
        Instruction ins;
        for (Iterator itr = inss.iterator(); itr.hasNext();) {
            ins = (Instruction) itr.next();
            if (ins.getByteIndex() == jumpByteIndex)
                return ins;
        }
        return null;
    }

    public void updateTargets() {
        super.updateTargets();
        for (Iterator itr = _cases.iterator(); itr.hasNext();)
            ((InstructionPtrStrategy) itr.next()).updateTargets();
    }

    public void replaceTarget(Instruction oldTarget, Instruction newTarget) {
        super.replaceTarget(oldTarget, newTarget);
        for (Iterator itr = _cases.iterator(); itr.hasNext();)
            ((InstructionPtrStrategy) itr.next()).replaceTarget(oldTarget,
                newTarget);
    }

    public void acceptVisit(BCVisitor visit) {
        visit.enterLookupSwitchInstruction(this);
        visit.exitLookupSwitchInstruction(this);
    }

    void read(Instruction orig) {
        super.read(orig);

        LookupSwitchInstruction ins = (LookupSwitchInstruction) orig;
        _matches = new LinkedList(ins._matches);
        _cases.clear();
        for (Iterator itr = ins._cases.iterator(); itr.hasNext();) {
            InstructionPtrStrategy origPtr = (InstructionPtrStrategy)itr.next();
            InstructionPtrStrategy newPtr = new InstructionPtrStrategy(this);
            newPtr.setByteIndex(origPtr.getByteIndex());
            _cases.add(newPtr);
        }
        invalidateByteIndexes();
    }

    void read(DataInput in) throws IOException {
        // don't call super
        int bi = getByteIndex();
        for (int byteIndex = bi + 1; (byteIndex % 4) != 0; byteIndex++)
            in.readByte();

        setOffset(in.readInt());
        _matches.clear();
        _cases.clear();
        for (int i = 0, pairCount = in.readInt(); i < pairCount; i++) {
            _matches.add(Numbers.valueOf(in.readInt()));
            InstructionPtrStrategy next = new InstructionPtrStrategy(this);
            next.setByteIndex(bi + in.readInt());
            _cases.add(next);
        }
    }

    void write(DataOutput out) throws IOException {
        // don't call super
        int bi = getByteIndex();
        for (int byteIndex = bi + 1; (byteIndex % 4) != 0; byteIndex++)
            out.writeByte(0);

        out.writeInt(getOffset());
        out.writeInt(_matches.size());
        for (int i = 0; i < _matches.size(); i++) {
            out.writeInt(((Integer) _matches.get(i)).intValue());
            out.writeInt(((InstructionPtrStrategy) _cases.get(i)).getByteIndex()
                - bi);
        }
    }
}
