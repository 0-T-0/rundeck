package com.dtolabs.rundeck.app.internal.workflow

import com.dtolabs.rundeck.core.execution.workflow.state.*

/**
 * $INTERFACE is ...
 * User: greg
 * Date: 10/15/13
 * Time: 3:41 PM
 */
class MutableWorkflowStateImpl implements MutableWorkflowState {
    def ArrayList<String> mutableNodeSet;
    def ArrayList<String> mutableAllNodes;
    def long stepCount;
    def ExecutionState executionState;
    def Date timestamp;
    def Date startTime;
    def Date endTime;
    def Map<Integer,MutableWorkflowStepState> mutableStepStates;
    def Map<String,MutableWorkflowNodeState> mutableNodeStates;
    def List<Map<Date,Map>> stateChanges=[]
    private StepIdentifier parentStepId

    MutableWorkflowStateImpl(List<String> nodeSet, long stepCount) {
        this(nodeSet,stepCount,null)
    }
    MutableWorkflowStateImpl(List<String> nodeSet, long stepCount, Map<Integer, MutableWorkflowStepStateImpl> steps) {
        this(nodeSet,stepCount,steps,null)
    }
    MutableWorkflowStateImpl(List<String> nodeSet, long stepCount, Map<Integer, MutableWorkflowStepStateImpl> steps, StepIdentifier parentStepId) {
        this.parentStepId=parentStepId
        this.mutableNodeSet = new ArrayList<>()
        this.mutableAllNodes = new ArrayList<>()
        if(null!=nodeSet){
            this.mutableNodeSet.addAll(nodeSet)
        }
        this.mutableAllNodes.addAll(mutableNodeSet)
        this.stepCount = stepCount
        mutableStepStates = new HashMap<Integer,MutableWorkflowStepState>()
        for (int i = 1; i <= stepCount; i++) {
            mutableStepStates[i - 1] = steps && steps[i-1]? steps[i-1] : new MutableWorkflowStepStateImpl(StateUtils.stepIdentifierAppend(parentStepId, StateUtils.stepIdentifier(i)))
        }
        this.executionState=ExecutionState.WAITING
        mutableNodeStates = new HashMap<String, MutableWorkflowNodeState>()
        mutableAllNodes.each {node->
            mutableNodeStates[node]=new MutableWorkflowNodeStateImpl(node)
        }
        if(mutableNodeStates && mutableStepStates){
            //link nodes to node step states
            mutableNodeStates.each { String node, MutableWorkflowNodeState nstate->
                mutableStepStates.each { int index, MutableWorkflowStepState step->
                    if(step.nodeStep){
                        getOrCreateMutableNodeStepState(step, node, step.stepIdentifier)
                    }
                }
            }
        }

    }

    MutableWorkflowStepState getAt(Integer index){
        return mutableStepStates[index-1]
    }

    @Override
    List<WorkflowStepState> getStepStates() {
        return mutableStepStates.sort().values() as List
    }

    @Override
    Map<String,? extends WorkflowNodeState> getNodeStates() {
        return mutableNodeStates
    }

    @Override
    List<String> getNodeSet() {
        return mutableNodeSet
    }

    @Override
    List<String> getAllNodes() {
        return mutableAllNodes
    }

    @Override
    void updateStateForStep(StepIdentifier identifier,StepStateChange stepStateChange, Date timestamp) {
        updateStateForStep(identifier,0,stepStateChange,timestamp)
    }
    @Override
    void updateStateForStep(StepIdentifier identifier, int index,StepStateChange stepStateChange, Date timestamp) {
        touchWFState(timestamp)

        Map<Integer, MutableWorkflowStepState> states = mutableStepStates;
        MutableWorkflowStepState currentStep = locateStepWithContext(identifier, index,states)
        if (identifier.context.size() - index > 1) {
            descendUpdateStateForStep(currentStep, identifier, index, stepStateChange, timestamp)
            return
        }
        addStateChange(timestamp, stepStateChange)

        //update the step found
        MutableStepState toUpdate
        if (stepStateChange.isNodeState()) {
            //find node state in stepstate
            def nodeName = stepStateChange.nodeName
            toUpdate = getOrCreateMutableNodeStepState(currentStep, nodeName, identifier)
            toUpdate.executionState = updateState(toUpdate.executionState, stepStateChange.stepState.executionState)
            if (!currentStep.nodeStep && nodeSet) {
                if (null == currentStep.nodeStepTargets || currentStep.nodeStepTargets.size() < 1) {
                    currentStep.setNodeStepTargets(nodeSet)
                }
            }
            mutableNodeStates[nodeName].mutableNodeState.executionState = toUpdate.executionState

            //TODO: need to merge this data
            mutableNodeStates[nodeName].mutableNodeState.metadata = toUpdate.metadata
            mutableNodeStates[nodeName].mutableNodeState.errorMessage = toUpdate.errorMessage
            mutableNodeStates[nodeName].mutableNodeState.updateTime = toUpdate.updateTime
            mutableNodeStates[nodeName].mutableNodeState.startTime = toUpdate.startTime
            mutableNodeStates[nodeName].mutableNodeState.endTime = toUpdate.endTime

            mutableNodeStates[nodeName].lastIdentifier = identifier
        } else if (!currentStep.nodeStep) {
            //overall step state
            toUpdate = currentStep.mutableStepState

            toUpdate.executionState = updateState(toUpdate.executionState, stepStateChange.stepState.executionState)
        } else {
            toUpdate = currentStep.mutableStepState
            if (nodeSet && (null == currentStep.nodeStepTargets || currentStep.nodeStepTargets.size() < 1)) {
                currentStep.setNodeStepTargets(nodeSet)
            }
        }
        transitionIfWaiting(currentStep.mutableStepState)

        //update state
        toUpdate.errorMessage = stepStateChange.stepState.errorMessage
        if (stepStateChange.stepState.metadata) {
            if (null == toUpdate.metadata) {
                toUpdate.metadata = [:]
            }
            toUpdate.metadata << stepStateChange.stepState.metadata
        }
        if (!toUpdate.startTime) {
            toUpdate.startTime = timestamp
        }
        toUpdate.updateTime = timestamp
        if (toUpdate.executionState.isCompletedState()) {
            toUpdate.endTime = timestamp
        }

        if (stepStateChange.isNodeState() && currentStep.nodeStep && stepStateChange.stepState.executionState.isCompletedState()) {
            //if all target nodes have completed execution state, mark the overall step state
            finishNodeStepIfNodesFinished(currentStep, timestamp)
        } else if (stepStateChange.isNodeState() && currentStep.nodeStep && currentStep.stepState.executionState.isCompletedState()
                && stepStateChange.stepState.executionState == ExecutionState.RUNNING_HANDLER) {
            currentStep.mutableStepState.executionState = ExecutionState.RUNNING_HANDLER
        }
    }

    /**
     * For a node and step, create or return the shared node+step mutable state
     * @param currentStep
     * @param nodeName
     * @param identifier
     * @return
     */
    private MutableStepState getOrCreateMutableNodeStepState(MutableWorkflowStepState currentStep, String nodeName, StepIdentifier identifier) {
        if (null == currentStep.nodeStateMap[nodeName]) {
            //create it
            currentStep.mutableNodeStateMap[nodeName] = new MutableStepStateImpl()
        }
        //connect step-oriented state to node-oriented state
        if (null == mutableNodeStates[nodeName]) {
            mutableNodeStates[nodeName] = new MutableWorkflowNodeStateImpl(nodeName)
        }
        if (null == mutableNodeStates[nodeName].mutableStepStateMap[identifier]) {
            mutableNodeStates[nodeName].mutableStepStateMap[identifier] = currentStep.mutableNodeStateMap[nodeName]
        }
        return currentStep.mutableNodeStateMap[nodeName]
    }

    private void addStateChange(Date timestamp, StepStateChange stepStateChange) {
        this.stateChanges << [(timestamp): asChangeMap(stepStateChange)]
    }

    static Map asChangeMap(StepStateChange stepStateChange) {
        [
                node: stepStateChange.nodeName,
                nodeState: stepStateChange.nodeState,
        ] + asChangeMap(stepStateChange.stepState)
    }

    static Map asChangeMap(StepState stepState) {
        [
                errorMessage: stepState.errorMessage,
                executionState: stepState.executionState.toString(),
                meta: stepState.metadata,
        ]
    }
/**
     * Descend into a sub workflow to update state
     * @param currentStep
     * @param identifier
     * @param stepStateChange
     * @param timestamp
     */
    private void descendUpdateStateForStep(MutableWorkflowStepState currentStep, StepIdentifier identifier, int index,StepStateChange stepStateChange, Date timestamp) {
        transitionIfWaiting(currentStep.mutableStepState)
        //recurse to the workflow list to find the right index

        MutableWorkflowState subflow = currentStep.hasSubWorkflow() ?
            currentStep.mutableSubWorkflowState :
            currentStep.createMutableSubWorkflowState(null, 0)
        //recursively update subworkflow state for the step in the subcontext
        subflow.updateStateForStep(identifier, index + 1, stepStateChange, timestamp);
    }

    private finishNodeStepIfNodesFinished(MutableWorkflowStepState currentStep,Date timestamp){
        boolean finished = currentStep.nodeStepTargets.every { node -> currentStep.nodeStateMap[node]?.executionState?.isCompletedState() }
        if (finished) {
            boolean aborted = currentStep.nodeStateMap.values()*.executionState.any { it == ExecutionState.ABORTED }
            boolean failed = currentStep.nodeStateMap.values()*.executionState.any { it == ExecutionState.FAILED }
            def overall = aborted ? ExecutionState.ABORTED : failed ? ExecutionState.FAILED : ExecutionState.SUCCEEDED
            finalizeNodeStep(overall, currentStep,timestamp)
        }
    }
    private finalizeNodeStep(ExecutionState overall, MutableWorkflowStepState currentStep,Date timestamp){
        def nodeTargets = currentStep.nodeStepTargets?:this.nodeSet
        boolean finished = currentStep.nodeStateMap && nodeTargets?.every { node -> currentStep.nodeStateMap[node]?.executionState?.isCompletedState() }
        boolean aborted = currentStep.nodeStateMap && currentStep.nodeStateMap?.values()*.executionState.any { it == ExecutionState.ABORTED }
        boolean abortedAll = currentStep.nodeStateMap && currentStep.nodeStateMap?.values()*.executionState.every { it == ExecutionState.ABORTED }
        boolean failed = currentStep.nodeStateMap && currentStep.nodeStateMap?.values()*.executionState.any { it == ExecutionState.FAILED }
        boolean failedAll = currentStep.nodeStateMap && currentStep.nodeStateMap?.values()*.executionState.every { it == ExecutionState.FAILED }
        boolean succeeded = currentStep.nodeStateMap && currentStep.nodeStateMap?.values()*.executionState.any { it == ExecutionState.SUCCEEDED }
        boolean succeededAll = currentStep.nodeStateMap && currentStep.nodeStateMap?.values()*.executionState.every { it == ExecutionState.SUCCEEDED }
        boolean notStartedAll = currentStep.nodeStateMap?.size() == 0 ||
                currentStep.nodeStateMap?.values()*.executionState.every { it == ExecutionState.WAITING || it == null }
        ExecutionState result=overall
        if(finished){
            //all nodes finished
            if(abortedAll){
                result=ExecutionState.ABORTED
            }else if(failedAll){
                result=ExecutionState.FAILED
            }else if(succeededAll){
                result=ExecutionState.SUCCEEDED
            }else{
                result=ExecutionState.NODE_MIXED
            }
        }else if (aborted && !failed && !succeeded) {
            //partial aborted
            result = ExecutionState.ABORTED
        } else if (!aborted && failed && !succeeded) {
            //partial failed
            result = ExecutionState.FAILED
        } else if (!failed && !aborted && succeeded) {
            //partial success
            result = ExecutionState.NODE_PARTIAL_SUCCEEDED
        }else if (notStartedAll) {
            //not started
            result = ExecutionState.NOT_STARTED
        } else {
            result = ExecutionState.NODE_MIXED
        }

        if(currentStep.nodeStep || currentStep.hasSubWorkflow()){
            currentStep.mutableStepState.executionState = result
        }else{
            currentStep.mutableStepState.executionState = updateState(currentStep.mutableStepState.executionState, result)
        }
        currentStep.mutableStepState.endTime=timestamp

        //update any node states which are WAITING to NOT_STARTED
        nodeTargets.each{String node->
            if(!currentStep.mutableNodeStateMap[node]){
                currentStep.mutableNodeStateMap[node] = new MutableStepStateImpl(executionState:ExecutionState.WAITING)
            }
            MutableStepState state = currentStep.mutableNodeStateMap[node]
            if (state && state.executionState == ExecutionState.WAITING) {
                state.executionState = updateState(state.executionState, ExecutionState.NOT_STARTED)
                state.endTime=timestamp
            }
        }
    }

    private MutableWorkflowStepState locateStepWithContext(StepIdentifier identifier, int index,Map<Integer, MutableWorkflowStepState> states) {
        MutableWorkflowStepState currentStep
        StepContextId subid = identifier.context[index]
        int ndx=subid.step-1
        if (ndx >= states.size() || null == states[ndx]) {
            states[ndx] = new MutableWorkflowStepStateImpl(StateUtils.stepIdentifier(subid))
            stepCount = states.size()
        }
        currentStep = states[ndx]
        currentStep
    }

    private void touchWFState(Date timestamp) {
        executionState = transitionStateIfWaiting(executionState)
        if (null == this.timestamp || this.timestamp < timestamp) {
            this.timestamp = timestamp
        }
        if (null == this.startTime) {
            this.startTime = timestamp
        }
    }


    private void transitionIfWaiting(MutableStepState step) {
        step.executionState = transitionStateIfWaiting(step.executionState)
    }
    private ExecutionState transitionStateIfWaiting(ExecutionState state) {
        if (waitingState(state)) {
            return updateState(state, ExecutionState.RUNNING)
        }else{
            return state
        }
    }

    private static boolean waitingState(ExecutionState state) {
        null == state || state == ExecutionState.WAITING
    }

    /**
     * Update state change
     * @param fromState
     * @param toState
     * @return
     */
    public static ExecutionState updateState(ExecutionState fromState, ExecutionState toState) {
        if(fromState==toState){
            return toState
        }
        def allowed=[
                (ExecutionState.WAITING):[null,ExecutionState.WAITING],
                (ExecutionState.RUNNING): [null, ExecutionState.WAITING,ExecutionState.RUNNING],
                (ExecutionState.RUNNING_HANDLER):[null,ExecutionState.WAITING,ExecutionState.FAILED, ExecutionState.RUNNING,ExecutionState.RUNNING_HANDLER],
        ]
        ExecutionState.values().findAll{it.isCompletedState()}.each{
            allowed[it]= [it,ExecutionState.RUNNING, ExecutionState.RUNNING_HANDLER, ExecutionState.WAITING]
        }
        if (toState == null) {
//            System.err.println("Cannot change state to ${toState}")
            throw new IllegalStateException("Cannot change state to ${toState}")
        }
        if(!(fromState in allowed[toState])){
//            System.err.println("Cannot change from " + fromState + " to " + toState)
            throw new IllegalStateException("Cannot change from " + fromState + " to " + toState)
        }

        toState
    }

    @Override
    void updateWorkflowState(ExecutionState executionState, Date timestamp, List<String> nodenames) {
        updateWorkflowState(null,executionState,timestamp,nodenames,this)
    }
    void updateWorkflowState(StepIdentifier identifier,ExecutionState executionState, Date timestamp, List<String> nodenames, MutableWorkflowState parent) {
        touchWFState(timestamp)
        this.executionState = updateState(this.executionState, executionState)
        if (null != nodenames && (null == mutableNodeSet || mutableNodeSet.size() < 1)) {
            mutableNodeSet = new ArrayList<>(nodenames)
            def mutableNodeStates=parent.mutableNodeStates
            def allNodes=parent.allNodes
            mutableNodeSet.each { node ->
                if(!mutableNodeStates[node]){
                    mutableNodeStates[node] = new MutableWorkflowNodeStateImpl(node)
                }
                if(!allNodes.contains(node)){
                    allNodes<<node
                }
                mutableStepStates.keySet().each {int ident->
                    if(mutableStepStates[ident].nodeStep){
                        if(null==mutableStepStates[ident].mutableNodeStateMap[node]){
                            mutableStepStates[ident].mutableNodeStateMap[node]=new MutableStepStateImpl()
                        }
                        mutableNodeStates[node].mutableStepStateMap[StateUtils.stepIdentifierAppend(identifier,StateUtils.stepIdentifier(ident + 1))]= mutableStepStates[ident].mutableNodeStateMap[node]
                    }
                }
            }
        }else if(null!=nodenames){
            def allNodes = parent.allNodes
            nodenames.each { node ->
                if (!allNodes.contains(node)) {
                    allNodes << node
                }
            }
        }
        if(executionState.isCompletedState()){
            cleanupSteps(executionState, timestamp)
            this.endTime=timestamp
        }
    }

    private void cleanupSteps(ExecutionState executionState, Date timestamp) {
        mutableStepStates.each { i, step ->
            if (!step.stepState.executionState.isCompletedState()) {
                resolveStepCompleted(executionState, timestamp, i+1, step)
            }
        }
    }

    /**
     * Resolve the completed state of a step based on overal workflow completion state
     * @param executionState
     * @param date
     * @param i
     * @param mutableWorkflowStepState
     */
    def resolveStepCompleted(ExecutionState executionState, Date date, int i, MutableWorkflowStepState mutableWorkflowStepState) {
        if(mutableWorkflowStepState.nodeStep){
            //a node step
            finalizeNodeStep(executionState,mutableWorkflowStepState,date)
        }else {
            def curstate= mutableWorkflowStepState.mutableStepState.executionState
            def newstate=executionState
            switch (curstate){
                case null:
                case ExecutionState.WAITING:
                    newstate=ExecutionState.NOT_STARTED
                    break
                case ExecutionState.RUNNING:
                    newstate = ExecutionState.ABORTED
                    break
            }
            mutableWorkflowStepState.mutableStepState.executionState = updateState(curstate, newstate)
            mutableWorkflowStepState.mutableStepState.endTime= date
        }
    }

    @Override
    void updateSubWorkflowState(StepIdentifier identifier, int index,ExecutionState executionState, Date timestamp, List<String> nodeNames, MutableWorkflowState parent) {
        touchWFState(timestamp)
        Map<Integer, MutableWorkflowStepState> states = mutableStepStates;

        if (identifier.context.size() - index > 0) {
            //descend one step
            MutableWorkflowStepState nextStep = locateStepWithContext(identifier, index, states)
            MutableWorkflowState nextWorkflow = nextStep.hasSubWorkflow() ?
                nextStep.mutableSubWorkflowState :
                nextStep.createMutableSubWorkflowState(null, 0)

            transitionIfWaiting(nextStep.mutableStepState)
            //more steps to descend
            nextWorkflow.updateSubWorkflowState(identifier, index + 1, executionState, timestamp, nodeNames, parent ?: this);
        } else {
            //update the workflow state for this workflow
            updateWorkflowState(identifier, executionState, timestamp, nodeNames, parent ?: this)
        }
    }

    @Override
    public java.lang.String toString() {
        return "WF{" +
                "nodes=" + mutableNodeSet +
                ", stepCount=" + stepCount +
                ", state=" + executionState +
                ", timestamp=" + timestamp +
                ", steps=" + mutableStepStates +
                '}';
    }
}