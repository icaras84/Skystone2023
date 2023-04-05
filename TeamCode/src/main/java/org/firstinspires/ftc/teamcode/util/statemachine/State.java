package org.firstinspires.ftc.teamcode.util.statemachine;

import android.os.Build;

import org.firstinspires.ftc.teamcode.util.general.functionalinterfaces.Supplier;
import org.firstinspires.ftc.teamcode.util.general.misc.GeneralConstants;

import java.util.Stack;
import java.util.concurrent.ForkJoinPool;

/**
 * This interface can be implemented to give a "command" that is dedicated to a single job.
 * It also allows the ability to put those states in a "State Machine" ({@code State.Sequence})
 */
public interface State extends Runnable{

    /**
     * This empty state contains nothing and runs nothing
     */
    final class Empty implements State{

        public Empty(){}

        @Override public void init() {}
        @Override public void run() {}
        @Override public void end() {}

        @Override
        public boolean isFinished() {
            return true;
        }
    }

    /**
     * This state is a special command for the state machine to wait
     * (non-blocking, so the main thread can still proceed)
     */
    class Wait implements State{
        private long durationMS;

        private long lastTime = 0L, currTime = 0L, deltaTime = 0L, projectedTime = 0L;
        public Wait(long ms){
            this.durationMS = ms;
        }

        @Override
        public void init() {
            lastTime = System.currentTimeMillis();
            currTime = System.currentTimeMillis();
        }

        @Override
        public void run() {
            currTime = System.currentTimeMillis();
            deltaTime = currTime - lastTime;
            projectedTime = currTime + deltaTime;
            lastTime = currTime;
        }

        @Override
        public void end() {

        }

        @Override
        public boolean isFinished() {
            return currTime + (0.02 * deltaTime) >= durationMS;
        }

        public long getDeltaTimeMS(){
            return this.deltaTime;
        }

        public long getProjectedTimeMS(){
            return this.projectedTime;
        }
    }

    /**
     * This state is a special command for the state machine to wait
     * until a certain flag pops-up
     * @param <T>
     */
    class WaitFor<T extends Flag<?>> implements State{
        private T monitoredFlag;
        private T targetFlag;
        private boolean exit;
        public WaitFor(T flag, T target){
            this.monitoredFlag = flag;
            this.targetFlag = target;
        }

        @Override
        public void init() {}

        @Override
        public void run() {
            this.exit = monitoredFlag.getCurrentState().equals(targetFlag);
        }

        @Override
        public void end() {}

        @Override
        public boolean isFinished() {
            return this.exit;
        }
    }

    /**
     * This state is self-explanatory as IF a condition is true, the state in the truth body runs,
     * but while false the state in the false body runs
     */
    class If implements State {

        private State t, f;
        private Supplier<Boolean> conditional;

        public If(Supplier<Boolean> condition, State conditionTrue, State conditionFalse){
            this.t = conditionTrue;
            this.f = conditionFalse;
            this.conditional = condition;
        }

        @Override
        public void init() {
            if (conditional.get()){
                t.init();
            } else {
                f.init();
            }
        }

        @Override
        public void run() {
            if (conditional.get()){
                t.run();
            } else {
                f.run();
            }
        }

        @Override
        public void end() {
            if (conditional.get()){
                t.end();
            } else {
                f.end();
            }
        }

        @Override
        public boolean isFinished() {
            if (conditional.get()){
                return t.isFinished();
            } else {
                return f.isFinished();
            }
        }
    }

    /**
     * This state is self-explanatory as WHILE a condition is true, the states in the body run
     */
    class While implements State {

        private State[] states;
        private Supplier<Boolean> conditional;
        private Sequence internalStateManager;

        public While(Supplier<Boolean> condition, State... states){
            this.conditional = condition;
            this.states = states;
            this.internalStateManager = new Sequence();
        }

        @Override
        public void init() {
            reloadStateStack();
        }

        @Override
        public void run() {
            if (conditional.get()){
                internalStateManager.run();
                if (internalStateManager.hasNoStates()){
                    reloadStateStack();
                }
            }
        }

        private void reloadStateStack(){
            this.internalStateManager.addAll(states.clone());
        }

        @Override
        public void end(){

        }

        @Override
        public boolean isFinished() {
            return !conditional.get();
        }
    }

    /**
     * This is a specialized version of the WHILE state where it strictly operates on an iterator
     */
    class For implements State {
        private int i, loopLimit;

        private State[] cachedStates;
        private Sequence internalStateManager;

        public For(int loopCount, State... states){
            this.cachedStates = states;
            this.internalStateManager = new Sequence();

            this.loopLimit = loopCount;
        }

        @Override
        public void init() {
            this.i = 0;
            reloadStateStack();
        }

        private void reloadStateStack(){
            this.internalStateManager.addAll(this.cachedStates);
        }

        @Override
        public void run() {
            if (!isFinished()){
                internalStateManager.run();
                if (internalStateManager.hasNoStates()){
                    reloadStateStack();
                    this.i++;
                }
            }
        }

        @Override
        public void end() {

        }

        @Override
        public boolean isFinished() {
            return this.i >= this.loopLimit;
        }

        public int getI(){
            return this.i;
        }
    }

    /**
     * This state specifies what needs to be run at the same time, but this state finishes after
     * every state finishes
     */
    class AsyncGroup implements State {

        private State[] states;
        private boolean[] finishMask;

        public AsyncGroup(State... states){
            this.states = states;
            this.finishMask = new boolean[this.states.length];
        }

        @Override
        public void init() {
            for (State s: states) {
                s.init();
            }
        }

        @Override
        public void run() {
            for (int i = 0; i < states.length; i++) {
                if (!finishMask[i]){
                    states[i].run();
                }
            }

            for (int i = 0; i < states.length; i++) {
                if (states[i].isFinished()){
                    states[i].end();
                    finishMask[i] = true;
                }
            }
        }

        @Override
        public void end() {

        }

        @Override
        public boolean isFinished() {
            boolean output = true;
            for (boolean b: finishMask) {
                if (!b) output = false;
            }
            return output;
        }
    }

    /**
     * This state is a special type of {@code AsyncGroup} state where it puts every state on an
     * async group and tells it to run in a separate thread, finishing immediately
     */
    class ParallelAsyncGroup implements State {

        private volatile Sequence internalStateManager;
        private volatile State[] states;

        public ParallelAsyncGroup(State... states){
            this.states = states;
            this.internalStateManager = new Sequence();
            this.internalStateManager.addAll(states);
        }

        @Override
        public void init() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ForkJoinPool.commonPool().submit(
                        () -> {
                            while (!internalStateManager.hasNoStates()) {
                                internalStateManager.run();
                            }});
            } else {
                new Thread(() -> {
                    while (!internalStateManager.hasNoStates()) {
                        internalStateManager.run();
                    }});
            }
        }

        @Override
        public void run() {

        }

        @Override
        public void end() {

        }

        @Override
        public boolean isFinished() {
            return true;
        }
    }

    /**
     * This state is akin to a "State Machine" where it details a specified sequence of states
     * it needs to run and has logic for handling that
     */
    class Sequence implements State{
        private Stack<State> stateStack;
        private long startTime;

        private boolean sInit = false;
        private State runningState = null;

        public Sequence(){
            stateStack = new Stack<>();
        }

        public Sequence(Sequence stateSequence){
            this.stateStack = (Stack<State>) stateSequence.stateStack.clone();
        }

        public Sequence add(State state){
            stateStack.add(0, state);
            return this;
        }

        public Sequence addAll(State... states){
            for (State s: states) {
                add(s);
            }
            return this;
        }

        public Sequence addSequence(Sequence sequence){
            addAll(sequence.getSequence());
            return this;
        }

        public State[] getSequence(){
            State[] states = stateStack.toArray(new State[0]);
            return states;
        }

        public Sequence addToNext(State state){
            stateStack.insertElementAt(state, stateStack.size() - 2);
            return this;
        }

        @Override
        public void init() {

        }

        public void run(){
            if (!stateStack.empty()) { //check if the stack is filled
                if (!sInit) { //test if the current state has initialized (default is false)
                    startTime = System.currentTimeMillis();

                    runningState = stateStack.peek();
                    runningState.init();
                    sInit = true; //flag true after running initialization to prevent another init call
                }

                if (!runningState.isFinished()) runningState.run();

                if (runningState.isFinished()) { //check if current state is finished
                    runningState.end(); //call end() of current state
                    stateStack.pop(); //dispose of the state
                    sInit = false; //flag that the next state needs to initialize
                }
            } else {
                startTime = System.currentTimeMillis();
            }
        }

        @Override
        public void end() {

        }

        @Override
        public boolean isFinished() {
            return isEmpty();
        }

        public boolean hasNoStates(){
            return stateStack.empty();
        }

        public boolean isEmpty(){
            return stateStack.empty();
        }

        public long getCurrentStateElapsedTimeMS(){
            return System.currentTimeMillis() - startTime;
        }

        public long getCurrentStateElapsedTimeSEC(){
            return (long) (getCurrentStateElapsedTimeMS() * GeneralConstants.MS2SEC);
        }

        public long getStartTimeMS(){
            return startTime;
        }

        public long getStartTimeSEC(){
            return (long) (startTime * GeneralConstants.MS2SEC);
        }
    }

    void init();
    void run();
    void end();
    boolean isFinished();
}