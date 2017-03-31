package mesme;

import java.util.HashMap;

import mesme.MweEngineDefinition;

/*
 * MweStateEngine is a configurable state machine for the MESME MWE extractor
 * It has a default setting for English with the POS tag set output by TwitIE
 */
public class MweStateEngine {
  public static final int ADD_TOKEN = 1;									// add current token to chunk
  public static final int CLEAR_CHUNK = 2;								// clear current chunk
  public static final int CLEAR_ADD = 12;								  // clear current chunk & add current token
  public static final int SET_MWE = 3;										// copy chunk as a MWE
  public static final int SET_MWE_CLEAR = 4;							// copy chunk as MWE and clear current chunk
  public static final int SET_MWE_ADD = 13;							  // copy chunk as MWE and add current token
  public static final int SET_MWE_ADDEMBED = 14;					// copy chunk as embedded MWE
  public static final int SET_MWE_SUBTYPE = 5;						// copy chunk as MWE, flag as a 'sub-type'
  public static final int SET_MWE_SUBTYPE_CLEAR = 6;			// copy chunk as MWE, flag as a 'sub-type', clear current chunk
  public static final int ADD_SET_MWE = 7;								// add current token, copy chunk as MWE
  public static final int ADD_SET_MWE_CLEAR = 8;					// add current token, copy chunk as MWE and clear current chunk
  public static final int ADD_SET_MWE_SUBTYPE = 9;				// add current token, copy chunk as MWE, flag as a 'sub-type'
  public static final int ADD_SET_MWE_SUBTYPE_CLEAR = 10;	// add current token, copy chunk as MWE, flag as a 'sub-type', clear current chunk
  public static final int ADD_SET_MWE_SUBTYPE_CLEAR_ADD = 15;	// add current token, copy chunk as MWE, flag as a 'sub-type', clear current chunk, add token
  public static final int CONTINUE = 99;									// add the token if chunk is not empty (should stay in state)
  public static final int SUBSTATE = 98;									// Switch test to earlier tag
  public static final int SKIP = 97;									    // Skip current token 
  		
  private int numberOfStates = 0;
  private HashMap<String, State> engineStates = new HashMap<String, State>();
  
  public class State {
  	String name = "";
  	int stateID = 0;
  	HashMap<String, State> nextStateMap = new HashMap<String, State>(); // POS string and next state
  	HashMap<String, Integer> actionOption = new HashMap<String, Integer>(); // action option number given POS key
  	
  	public State (String stateName, int stateNumber) {
  		name = stateName;
  		stateID = stateNumber;
  	}
  }
  
  public String[] getStateNames () {
  	return (String[]) engineStates.keySet().toArray();
  }

  private StringBuffer chunking = new StringBuffer();
  public String getCurrentChunk () {
  	return chunking.toString();
  }
  
  public int chunkTokenCount = 0; // holds number of tokens in current chunk
  public int extractTokenCount = 0; // holds number of tokens in current extract
  
  private boolean mweState = false;
  public boolean isMWE () {
  	return mweState;
  }
  
  private int getActionCode (String actionName) {
  	int a = -1;
    try {
  	  a = this.getClass().getField(actionName).getInt(MweStateEngine.class);
    } catch (Exception e) {
    	System.err.println("Could not find valid action code for "+actionName);
    }
    return a;
  }
  
  public void addState (String stateName) {
  	if (engineStates.containsKey(stateName)) {
  		// state already in engine. Ignore for now
  		return;
  	}
  	numberOfStates++;
  	State newState = new State (stateName, numberOfStates);
  	engineStates.put(stateName, newState);
  }
	
  public void setStateActions (String stateName, String observedTag, int actionCode, String nextStateName) {
  	State pState = engineStates.get(stateName);
  	if (pState==null) {
  		addState (stateName);
  		pState = engineStates.get(stateName);
  	}
  	pState.actionOption.put(observedTag, actionCode);
  	State nextState = engineStates.get(nextStateName);
  	if (nextState==null) {
  		addState (nextStateName);
  		nextState = engineStates.get(nextStateName);
  	}  	
  	pState.nextStateMap.put(observedTag, nextState);
  }

  private void setState (String stateName) {
  	if (engineStates.containsKey(stateName)) {
  	  currentState = engineStates.get(stateName);
  	}
  }
  
  /*
   * initialise programs up the state engine with the specified configuration table
   * If no table is provided then the default MESME compound noun chunker engine is assumed
   * stateSpec should be a table of strings, each row comprising:
   * STATE, TAG, ACTION, NEXT-STATE
   * Returns FALSE if specification could not be used to create a valid state machine
   */
  public boolean initialise () {
  	return initialise (MweEngineDefinition.englishNominalCompounds);
  }
  
  public boolean initialise (String[] stateSpec) {
  	int noOfStates = stateSpec.length;
  	if (noOfStates % 4 !=0) return false;
  	
  	for (int i=0;i<noOfStates;i=i+4) {
  		addState (stateSpec[i]);
  		int action = getActionCode(stateSpec[i+2]);
  		setStateActions (stateSpec[i],stateSpec[i+1],action,stateSpec[i+3]);
  	}
  	setState (stateSpec[0]); // first specified state in definition is the default starting state
  	return true;
  }
  
  private String extract = null;
  public String getExtractMWE () {
  	String mwe = extract;
  	extract = null;
  	isTyped = false;
  	return mwe;
  }
  private int chunkStartPt = 0;
  public int chunkStartPt() {
  	return chunkStartPt;
  }
  private int chunkStartTagNo = 0;
  public int chunkStartIndex() {
  	return chunkStartTagNo;
  }
  private String embedChunk = "";
  public String getEmbed () {
  	String mwe = new String (embedChunk+" "+extract);
  	return mwe;
  }
  private int embedPoint = 0;
  public int embedOffset() {
  	return embedPoint;
  }
  private int embedTokenOffset = 0;
  public int embedTokenOffset() {
  	return embedTokenOffset;
  }
  private boolean potentialEmbed = false;
  public boolean hasEmbed() {
  	if (!potentialEmbed) return false;
  	if (extract==null) potentialEmbed = false;
  	//if (extract.length()<1) potentialEmbed = false;
  	return potentialEmbed;
  }
  private boolean isTyped = false;
  public boolean currentMweIsTyped() {
  	return isTyped;
  }
  private State currentState = null;
  public String getCurrentState () {
  	if (currentState == null) return null;
  	return currentState.name;
  }
  private String lastTag;
  private String priorTag;
  
  /*
   * stepEngine carries out the programmed action for the state machine given the observed tag and steps the engine to the next state 
   */
  public void stepEngine (String tag, String token, int indexPoint, int tagNo) {
    // Get and carry out action for tag in current state
  	String currentTag = tag;
  	if (!currentState.actionOption.containsKey(tag)) tag = "**";    
  	int action = currentState.actionOption.get(tag);
	  if (action==SUBSTATE) { // Need to set up secondary test
	  	String pt = priorTag;
	  	if (!currentState.nextStateMap.get(tag).actionOption.containsKey(priorTag)) pt = "**";
	    action = currentState.nextStateMap.get(tag).actionOption.get(pt);
	  }
	  switch (action) {
  	case (0) : break; // do nothing
  	case (ADD_TOKEN) : 
  		if (chunkTokenCount==0) {
  			chunkStartPt = indexPoint;
  			chunkStartTagNo = tagNo;
  		}
  		if (chunking.length() > 0) chunking.append(' '); else priorTag = lastTag;
  		chunking.append(token);
  		chunkTokenCount++;
  		break;
  	case (CLEAR_CHUNK) :
  		chunking.setLength(0);
	  	chunkTokenCount = 0;
	  	potentialEmbed = false;
  	  break;
  	case (CLEAR_ADD) :
  		chunking.setLength(0);
    	potentialEmbed = false;
    	priorTag = lastTag;
  		chunking.append(token);
  		chunkTokenCount=1;
  		chunkStartPt = indexPoint;
			chunkStartTagNo = tagNo;
  	  break;
  	case (SET_MWE) :
  		extract = chunking.toString();
  		extractTokenCount = chunkTokenCount;
  	  break;
  	case (SET_MWE_CLEAR) :
  		extract = chunking.toString();
		  extractTokenCount = chunkTokenCount;
		  chunking.setLength(0);
  		chunkTokenCount = 0;
	    break;  		
  	case(SET_MWE_ADD) :
  		extract = chunking.toString();
  		extractTokenCount = chunkTokenCount;
  		chunking.append(' ');
  		chunking.append(token);
  		chunkTokenCount++;
	    break;
  	case (SET_MWE_ADDEMBED) :
   		extract = chunking.toString();
 	  	extractTokenCount = chunkTokenCount;
  		chunking.append(' ');
      embedPoint = chunking.length();
      embedTokenOffset = extractTokenCount;
	  	isTyped = false;
  		chunking.append(token);
  		chunkTokenCount++;
  		potentialEmbed = true;
  		break;
  	case(SET_MWE_SUBTYPE) :
  		extract = chunking.toString();
  		extractTokenCount = chunkTokenCount;
  	  isTyped = true;
	    break;  		
  	case(SET_MWE_SUBTYPE_CLEAR) :
  		extract = chunking.toString();
  		extractTokenCount = chunkTokenCount;
  	  isTyped = true;
		  chunking.setLength(0);
  		chunkTokenCount = 0;
	  	potentialEmbed = false;
	    break;  		
  	case (ADD_SET_MWE) : 
  		if (chunkTokenCount==0) {
  			chunkStartPt = indexPoint;
  			chunkStartTagNo = tagNo;
  		}
  		if (chunking.length() > 0) chunking.append(' ');
		  chunking.append(token);
  		chunkTokenCount++;
  		extract = chunking.toString();
  		extractTokenCount = chunkTokenCount;
	  	isTyped = false;
		  break;
  	case (ADD_SET_MWE_CLEAR ) :
  		if (chunkTokenCount==0) {
  			chunkStartPt = indexPoint;
  			chunkStartTagNo = tagNo;
  		}
  		if (chunking.length() > 0) chunking.append(' ');
		  chunking.append(token);
  		chunkTokenCount++;
  		extract = chunking.toString();
  		extractTokenCount = chunkTokenCount;
	  	isTyped = false;
  		chunking.setLength(0);
  		chunkTokenCount = 0;
	  	potentialEmbed = false;
		  break;
  	case (ADD_SET_MWE_SUBTYPE) : 
  		if (chunkTokenCount==0) {
  			chunkStartPt = indexPoint;
  			chunkStartTagNo = tagNo;
  		}
  		if (chunking.length() > 0) chunking.append(' ');
		  chunking.append(token);
  		chunkTokenCount++;
		  isTyped = true;
      extract = chunking.toString();
  		extractTokenCount = chunkTokenCount;
		  break;
  	case (ADD_SET_MWE_SUBTYPE_CLEAR) : 
  		if (chunkTokenCount==0) {
  			chunkStartPt = indexPoint;
  			chunkStartTagNo = tagNo;
  		}
  		if (chunking.length() > 0) chunking.append(' ');
		  chunking.append(token);
  		chunkTokenCount++;
		  isTyped = true;
  		extract = chunking.toString();
  		extractTokenCount = chunkTokenCount;
  		chunking.setLength(0);
  		chunkTokenCount = 0;
	  	potentialEmbed = false;
		  break;
  	case (ADD_SET_MWE_SUBTYPE_CLEAR_ADD) : 
  		if (chunkTokenCount==0) {
  			chunkStartPt = indexPoint;
  			chunkStartTagNo = tagNo;
  		}
  		if (chunking.length() > 0) chunking.append(' ');
		  chunking.append(token);
  		chunkTokenCount++;
		  isTyped = true;
  		extract = chunking.toString();
  		extractTokenCount = chunkTokenCount;
  		chunking.setLength(0);
    	potentialEmbed = false;
    	priorTag = lastTag;
  		chunking.append(token);
  		chunkTokenCount=1;
  		chunkStartPt = indexPoint;
			chunkStartTagNo = tagNo;
  	  break;
  	case (CONTINUE) :
  		if (chunking.length() > 0) {
  			chunking.append(' ');
  			chunking.append(token);
  			chunkTokenCount++;
  		}
  	case (SKIP) : // do nothing
  	  break;
  	}

  	// update parameters and move engine to next state
  	lastTag = currentTag;
  	currentState = currentState.nextStateMap.get(tag);
  }
}
