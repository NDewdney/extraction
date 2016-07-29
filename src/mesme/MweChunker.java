package mesme;

import java.util.*;
import java.io.*;

public class MweChunker {
  /* Currently hard-coded states. This really ought to be a configurable number of states */
	private static final int NOUN = 1;
  private static final int PRP = 2;
  private static final int CC = 3;
  private static final int ADJ = 4;
  private static final int STOP = 0;
  private static final int VERB = 5;
  private static final int TO = 6;
  private static final int RP = 7;
  private static final int IN = 8;
  private static final int DT = 9;
  private static final int RB = 10;
  private static final int VBPRP = 11;
  private static final int VBDT = 12;
  
  private HashSet<String> posTagSet = new HashSet<String>();
  private String[] posTagList = {"$", "``", "''", "(", ")", ",", "--", ".", ":", "CC", "CD", "DT", "EX", "FW", "IN", "JJ", "JJR",
  		                           "JJS", "LS", "MD", "NN", "NNP", "NNPS", "NNS", "PDT", "POS", "PRP", "PRP$", "RB", "RBR", "RBS", 
  		                           "RP", "SYM", "TO", "UH", "VB", "VBD", "VBG", "VBN", "VBP", "VBZ", "WDT", "WP", "WP$", "WRB",
  		                           "USR", "URL", "RT", "HT", "#"};

  private HashMap<String,String> tag2tagMap = new HashMap<String,String>();
  private String[] tagMappings = {"RT", "UH", "HT", "NN", "#", "DT", "USR", "NNP"};
    
  private HashSet<String> filterSet = new HashSet<String>();
  private String[] filterTags = {"VB", "VBD", "VBG", "VBN", "VBP", "VBZ", "RB", "RBR", "RBS", "RP", "TO"};
    
  private HashSet<String> orthoSet = new HashSet<String>();
  private String[] orthoNames = {"punc", "numb", "lower", "lowNum", "upper", "upNum", "mixed", "mixNum"};

  private int orthoClass (String word) {
  	int ortho = 0;
  	char[] wordChrs = word.toCharArray();
  	for (int i=0;i<wordChrs.length;++i) {
  		char c = wordChrs[i];
  		if (c>='0' && c<='9') ortho = ortho | 1;
  		if (c>='a' && c<='z') ortho = ortho | 2;
  		if (c>='A' && c<='A') ortho = ortho | 4;
  	}
  	return ortho;
  }

  private MweSvmVectorMaker vectorMaker;
  
  /* switches for pattern sequence filtering */
  private boolean allLVCmode = false;
  
  public void setAllLVCmode (boolean mode) {
  	allLVCmode = mode;
  }
  
  public boolean getAllLVCmode () {
  	return allLVCmode;
  }
  
  /* chunker output lists - should be re-factored to be combined lists for each mwe type! */
	private LinkedList<String> npChunks = new LinkedList<String>();
  private LinkedList<String> lvcChunks = new LinkedList<String>();
	private LinkedList<String> vpcChunks = new LinkedList<String>();
	private LinkedList<String> pronouns = new LinkedList<String>();
	private LinkedList<Integer> npPositions = new LinkedList<Integer>();
	private LinkedList<Integer> lvcPositions = new LinkedList<Integer>();
	private LinkedList<Integer> vpcPositions = new LinkedList<Integer>();
	private LinkedList<Integer> npEndpoints = new LinkedList<Integer>();
	private LinkedList<Integer> lvcEndpoints = new LinkedList<Integer>();
	private LinkedList<Integer> vpcEndpoints = new LinkedList<Integer>();
	private LinkedList<String> npVectors = new LinkedList<String>();
  private LinkedList<String> lvcVectors = new LinkedList<String>();
	private LinkedList<String> vpcVectors = new LinkedList<String>();
	private LinkedList<String[]> npTags = new LinkedList<String[]>();
  private LinkedList<String[]> lvcTags = new LinkedList<String[]>();
	private LinkedList<String[]> vpcTags = new LinkedList<String[]>();

	private MweActiveLearner activeLearner = new MweActiveLearner();
	private boolean learning = false;
	private double thresholdNPC = 0.5;
	private double thresholdVPC = 0.5;
	private double thresholdLVC = 0.5;
	
	
  private void initParams () {
    for (int i=0;i<posTagList.length;++i) posTagSet.add(posTagList[i]);	
    for (int i=0;i<filterTags.length;++i) filterSet.add(filterTags[i]);	
    for (int i=0;i<orthoNames.length;++i) orthoSet.add(orthoNames[i]);
    for (int i=0;i<tagMappings.length;i+=2) tag2tagMap.put(tagMappings[i], tagMappings[i+1]);
    vectorMaker = new MweSvmVectorMaker(posTagSet, orthoSet);
  }
  
  /*
 * MweChunker takes a sequence of tags and the corresponding tokens and parses the tags. Sequences of tags that 
 * correspond to the bigram rules below are used to chunk the associated tokens into multi-word-expressions, and stored
 */

	public MweChunker (String posString, String tokenString) {
		initParams();
		chunk (posString, tokenString);
	}
	
	public MweChunker (String posString, String tokenString, boolean filterLVCpatterns) {
		initParams();
		allLVCmode = filterLVCpatterns;
		chunk (posString, tokenString);
	}
	
	public MweChunker () {
		initParams();
	}
	
	public MweChunker (boolean filterLVCpatterns) {
		initParams();
		allLVCmode = filterLVCpatterns;
	}

/*
 * set LVCpatterns: default is false. Setting true will allow VB [DT][JJ*] NN* patterns to be passed as potential LVCs
 */
	
	public void setLVCpatterns (boolean filterLVCpatterns) {
		allLVCmode = filterLVCpatterns;
	}

	public void setLearningMode (boolean learningOn, int updateRate) {
		if (learningOn == false) {
			learning = false;
			return;
		}
		if (activeLearner==null) activeLearner = new MweActiveLearner (); // need to set parameters
	}

	/*
  * POS tagging generally distinguishes tense of verb but not any deeper linguistic role. The verb 'to be' often signals a reference to
  * a subsequent concept, (which itself may be a MWE). The following noun is not going to modify the sense of "to be" and therefore although
  * the POS pattern suggests an LVC it is not. 
  */
	private String[] defeats = {"is", "was", "are", "were", "be", "been", "become", "became", "becomes", "becoming", "being"};
  private boolean defeatLVC(String phrase) {
  	if (phrase.contains(" ")) {
  	  String opening = phrase.substring(0,phrase.indexOf(' ')).toLowerCase();
  	  for (int i=0;i<defeats.length;++i) {
  		  if (opening.equals(defeats[i])) return true;
  	  }
  	}
  	return false;
  }

  /*
   * POS tagging generally distinguishes tense of verb but not any deeper linguistic role. 
   */
 	private String[] vpcDefeats = {"that", "this"};
  private boolean defeatVPC(String vpcPhrase) {
  	int ep = vpcPhrase.length();
  	if (vpcPhrase.endsWith(" ")) while (ep >0 && vpcPhrase.charAt(ep-1)==' ') ep--;
  	String phrase = vpcPhrase.substring(0,ep).toLowerCase();
  	if (phrase.contains(" ")) {
   	  String lastWord = phrase.substring(phrase.lastIndexOf(' ')+1);
   	  for (int i=0;i<vpcDefeats.length;++i) {
   		  if (lastWord.equals(vpcDefeats[i])) {
   		  	return true;
   		  }
   	  }
  	}
   	return false;
   }

  private void filterVPCs () {
  	MweSvmVectorMaker vectorMaker = new MweSvmVectorMaker(posTagSet, filterSet);
  	
  	Iterator<String> vpcIt = vpcChunks.iterator();
  	int lcount = 0;
    LinkedList<Integer> rems = new LinkedList<Integer>();
  	while (vpcIt.hasNext()) {
  		String vpc = vpcIt.next();
  		if (defeatVPC(vpc)||defeatLVC(vpc)) {
  			rems.add(lcount);
  		}
  		else lcount++;
  	}
  	Iterator<Integer> rIt = rems.iterator();
  	while (rIt.hasNext()) {
  		lcount = rIt.next();
			vpcChunks.remove(lcount);
			vpcPositions.remove(lcount);
  	}
  }
  
  /*
   * Stub code - will engage classifier at future date
   */
  private void addNPC (String phrase, int offset, String[] tags, String[] tokens, int startTokNo, int mweTokens) {
    // if just a single noun the don't bother to evaluate MWE
  	String[] tgs = new String[mweTokens];
  	if (mweTokens==1) {
      npChunks.add(new String(phrase));
    	npPositions.add(offset);
    	npVectors.add("none");
    	tgs[0] = tags[startTokNo];
    	npTags.add(tgs);
    	return;
    }
  	String[] tagWindow = new String[mweTokens+4];
  	String[] tokenWindow = new String[mweTokens];
  	String[] orthographics = new String[mweTokens+4];
  	int s = startTokNo - 2;
  	int e = startTokNo+mweTokens+2;
  	int i = 0;
  	while (s < e) {
  		if (s<0 || s>=(tokens.length-1)) {
  			tagWindow[i] = ".";
  			orthographics[i] = orthoNames[0];
  		} else {
  			tagWindow[i] = tags[s];
  			orthographics[i] = orthoNames[orthoClass(tokens[s])];
  		}
  		i++;
  		s++;
  	}
  	for (int j=0;j<mweTokens;++j) {
  		tokenWindow[j] = tokens[startTokNo+j];
  	}
  	String vector = vectorMaker.svmVector(tagWindow, tokenWindow, orthographics);
  	double[] classProbs = {1,0};
  	if (learning) {
  	  classProbs = activeLearner.evaluate(vector, "npcModel");  // confidence for each class. MWE should be class 1
  	}
  	// double[] classProbs = {0.1,0.9}; // cut out classifier for now
  	// Accept NPC if classes as such, i.e. class 1 prob above threshold (default 0.5)
  	if (classProbs[0] <= thresholdNPC) return;
    npChunks.add(new String(phrase));
  	npPositions.add(offset);
  	npVectors.add(vector);
  	for (int z=0;z<mweTokens;++z) tgs[z] = tags[startTokNo+z];
  	npTags.add(tags);
  }
  
  private void addLVC (String phrase, int offset, String[] tags, String[] tokens, int startTokNo, int mweTokens) {
  	String[] tagWindow = new String[mweTokens+4];
  	String[] tokenWindow = new String[mweTokens];
  	String[] orthographics = new String[mweTokens+4];
  	String[] tgs = new String[mweTokens];
  	int s = startTokNo - 2;
  	int e = startTokNo+mweTokens+2;
  	int i = 0;
  	while (s < e) {
  		if (s<0 || s>=(tokens.length-1)) {
  			tagWindow[i] = ".";
  			orthographics[i] = orthoNames[0];
  		} else {
  			tagWindow[i] = tags[s];
  			orthographics[i] = orthoNames[orthoClass(tokens[s])];
  		}
  		i++;
  		s++;
  	}
  	for (int j=0;j<mweTokens;++j) {
  		if (startTokNo+j < tokens.length) tokenWindow[j] = tokens[startTokNo+j]; else tokenWindow[j] = ".";
  	}
  	String vector = vectorMaker.svmVector(tagWindow, tokenWindow, orthographics);
  	double[] classProbs = {1,0};
  	if (learning) {
  	  classProbs = activeLearner.evaluate(vector, "lvcModel");  // confidence for each class. MWE should be class 1
  	}
  	// Accept NPC if classes as such, i.e. class 1 prob above threshold (default 0.5)
  	if (classProbs[0] <= thresholdLVC) return;
    lvcChunks.add(new String(phrase));
  	lvcPositions.add(offset);
  	lvcVectors.add(vector);
  	for (int z=0;z<mweTokens;++z) if (z+startTokNo < tags.length) tgs[z] = tags[startTokNo+z]; else tgs[z] = ".";
  	lvcTags.add(tgs);
  }
  
  private void addVPC (String phrase, int offset, String[] tags, String[] tokens, int startTokNo, int mweTokens) {
  	String[] tagWindow = new String[mweTokens+4];
  	String[] tokenWindow = new String[mweTokens];
  	String[] orthographics = new String[mweTokens+4];
  	String[] tgs = new String[mweTokens];
  	int s = startTokNo - 2;
  	int e = startTokNo+mweTokens+2;
  	int i = 0;
  	while (s < e) {
  		if (s<0 || s>=(tokens.length-1)) {
  			tagWindow[i] = ".";
  			orthographics[i] = orthoNames[0];
  		} else {
  			tagWindow[i] = tags[s];
  			orthographics[i] = orthoNames[orthoClass(tokens[s])];
  		}
  		i++;
  		s++;
  	}
  	for (int j=0;j<mweTokens;++j) {
  		tokenWindow[j] = tokens[startTokNo+j];
  	}
  	String vector = vectorMaker.svmVector(tagWindow, tokenWindow, orthographics);
  	double[] classProbs = {1,0};
  	if (learning) {
  	  classProbs = activeLearner.evaluate(vector, "vpcModel");  // confidence for each class. MWE should be class 1
  	}
  	// Accept NPC if classes as such, i.e. class 1 prob above threshold (default 0.5)
  	if (classProbs[0] <= thresholdVPC) return;
    if (phrase.endsWith(" \"")) phrase = phrase.substring(0, phrase.length()-2);
  	vpcChunks.add(new String(phrase));
  	vpcPositions.add(offset);
  	vpcVectors.add(vector);
  	for (int z=0;z<mweTokens;++z) if (z+startTokNo < tags.length) tgs[z] = tags[startTokNo+z]; else tgs[z] = ".";
    vpcTags.add(tgs);
  }  
  /*
	 * Chunk takes a sequence of tags and the corresponding tokens and parses the tags. Sequences of tags that 
	 * correspond to the bigram rules below are used to chunk the associated tokens into multi-word-expressions, and stored
	 */
  
	public void chunk (String posString, String tokenString) {
		chunk (posString, tokenString, null);
	}
	
	public void chunk (String posString, String tokenString, LinkedList<Integer>posPositions) {
		npChunks = new LinkedList<String>();
		vpcChunks = new LinkedList<String>();
		lvcChunks = new LinkedList<String>();
		pronouns = new LinkedList<String>();
		npPositions = new LinkedList<Integer>();
		vpcPositions = new LinkedList<Integer>();
		lvcPositions = new LinkedList<Integer>();
		npEndpoints = new LinkedList<Integer>();
		vpcEndpoints = new LinkedList<Integer>();
		lvcEndpoints = new LinkedList<Integer>();
		npTags = new LinkedList<String[]>();
		vpcTags = new LinkedList<String[]>();
		lvcTags = new LinkedList<String[]>();

    String tempString = "-";
		
		String tags = new String (posString+" .");
		String tokens = new String (tokenString+" .");
		StringTokenizer tagList;
		StringTokenizer tokenList;

		// REFACTORING: NEED TO WORK IN NUMBERED LIST OF TOKENS RATHER THAT STRINGBUFFER - LEAVE BUFFER TO LAST 
		
		tagList = new StringTokenizer(tags, " ");
		tokenList = new StringTokenizer(tokens, " ");
		int tagTotal = tagList.countTokens();
		int tokenTotal = tokenList.countTokens(); 
		String[] tagArray = new String[tagTotal];
		String[] tokenArray = new String[tokenTotal];
		int k = 0;
    while (tokenList.hasMoreTokens()) {
    	String tag = tagList.nextToken();
    	if (tag2tagMap.containsKey(tag)) tagArray[k] = tag2tagMap.get(tag); else tagArray[k] = tag;
    	tokenArray[k] = tokenList.nextToken();
    	k++;
    }
    
  	tagList = new StringTokenizer(tags, " ");
  	tokenList = new StringTokenizer(tokens, " ");
		Iterator<Integer> posIt = null;
		
		if (posPositions!=null) {
			posIt = posPositions.iterator();
		}
		
    // extract noun phrase chunks
		int lastState = STOP;
		String lastTag = ".";
		String priorTag = ".";
		String lastWord = "";
		int newState = 0;
		int offset = 0;
		int startPos = -1;
		int startTagNo = 0;
		int chunkTags = 0;
		int embedStartPos = -1;
		int embedPoint = 0;
		int embedStartTagNo = 0;
		int embedPriorTags = 0;
    int embedChunkTags = 0;
    
		StringBuffer chunk = new StringBuffer();
/*    while (tokenList.hasMoreTokens()) {
  	String pos = tagList.nextToken();
  	String word = tokenList.nextToken(); */
    for (int tokenNo=0;tokenNo<tokenTotal;++tokenNo) {
    	String pos = tagArray[tokenNo];
    	String word = tokenArray[tokenNo];
    	offset += tokenString.substring(offset).indexOf(word);
//System.out.println(tokenNo+">> "+word+" << >> "+tokenString.substring(offset, offset+word.length())+"\t\t"+offset);
    	if (posIt!=null) {
      	if (posIt.hasNext()) {
      		offset = posIt.next();
      	}
      }
      
      // Now, when appending increase chunk tag number, when setting zero, set tag number to zero, when first setting set first tag number
      // Should change storage of chunk to sub-routine and add token-sequence / engage filter & learner.
      
    	switch(lastState) {
    	  case(STOP) : if (pos.startsWith("JJ")) {
    	  	             chunk.setLength(0); chunkTags =0;
    	              	 startPos = offset; startTagNo = tokenNo;
    	              	 newState = ADJ;
    	                 }
    	               else if (pos.equals("NN") || pos.equals("NNS")) { // adding in gets 8% fa for v.few
    	  	             chunk.setLength(0); chunkTags =0;
    	              	 chunk.append(word); chunkTags++;
    	              	 startPos = offset; startTagNo = tokenNo;
    	              	 newState = NOUN;
    	               }     	               
    	               else if (pos.equals("DT")|| pos.startsWith("VB") || pos.equals("IN") || pos.equals("''") || pos.equals(",") || pos.startsWith("PRP")|| pos.equals("CD") || pos.equals(")") || pos.startsWith("JJ") || pos.equals("(") || pos.equals("UH")) {
                       chunk.setLength(0); chunkTags =0;
                       startPos = offset; startTagNo = tokenNo;
                       newState = DT;
                     }    	               
    	               else if (pos.equals("CC")) { // to adds
                       chunk.setLength(0); chunkTags =0;
    	              	 startPos = offset; startTagNo = tokenNo;
                       newState = CC;
                     }
    	               else {
    	              	 newState = STOP;
    	              	 chunk.setLength(0); chunkTags =0;
    	              	 startPos = offset; startTagNo = tokenNo;
    	               }
    	               break;
    	  case(DT) : if (pos.equals("JJ")) {
                     chunk.setLength(0); chunkTags =0;
       	             chunk.append(word); chunkTags++;
       	             startPos = offset; startTagNo = tokenNo;
       	             newState = ADJ;
                   }
                   else if (pos.equals("NN") || pos.equals("NNS")) {
       	             if (chunk.length()==0) {
       	            	 startPos = offset; startTagNo = tokenNo;
         	             chunk.append(word); chunkTags++;
       	             }
       	             else {
       	            	 chunk.append(" "+word); chunkTags++;
       	             }
       	             newState = NOUN;
                   } 
                   else if (pos.startsWith("VB") && (lastWord.equals("a") || lastWord.equals("the") || lastTag.equals("PRP$"))) { // experimental - will need switch as well 
                     chunk.setLength(0); chunkTags =0;
       	             chunk.append(word); chunkTags++;
       	             startPos = offset; startTagNo = tokenNo;
       	             newState = VERB;
                   }
                   else if (pos.equals("DT")|| pos.startsWith("VB") || pos.equals("IN") || pos.equals("''") || pos.equals(",") || pos.startsWith("PRP")|| pos.equals("CD")|| pos.startsWith("JJ") || pos.equals(")") || pos.equals("(")|| pos.equals("TO")) { //
                     chunk.setLength(0); chunkTags =0;
  	              	 startPos = offset; startTagNo = tokenNo;
  	              	 newState = DT;
                   }
                   else if (pos.equals("TO") ) { // 
                     chunk.setLength(0); chunkTags =0;
  	              	 startPos = offset; startTagNo = tokenNo;
  	              	 newState = DT;
                   }
 	

                   else {
       	             newState = STOP;
       	             // EXPERIMENT
       	             // offset = startPos-tokenArray[startTagNo].length(); tokenNo = startTagNo; embedStartTagNo = -1;
       	             chunk.setLength(0); chunkTags =0;
       	             startPos = offset; startTagNo = tokenNo;
                   }
                   break;
    	  case(CC)   : if (pos.equals("JJ")) {
                       chunk.setLength(0); chunkTags =0;
  	              		 chunk.append(word); chunkTags++; 
    	              	 startPos = offset; startTagNo = tokenNo;
    	              	 newState = ADJ;
                     }    	              
    	               else if (pos.equals("DT")|| pos.startsWith("VB") || pos.equals("IN") || pos.equals("''") || pos.equals(",") || pos.startsWith("PRP")|| pos.equals("CD") || pos.equals(")") || pos.equals("(")|| pos.equals("TO")) {  
                       chunk.setLength(0); chunkTags =0;
    	              	 startPos = offset; startTagNo = tokenNo;
    	              	 newState = DT;
                     }
                     else if (pos.startsWith("VB")) {   
                       chunk.setLength(0); chunkTags =0;
         	             chunk.append(word); chunkTags++;
         	             startPos = offset; startTagNo = tokenNo;
         	             newState = VERB;
                     }
                     else if (pos.equals("NN") || pos.equals("NNS")) {
    	              	 if (chunk.length()==0) {
    	              		 chunk.append(word); chunkTags++;
    	              		 startPos = offset; startTagNo = tokenNo; 
    	              	 } else {
         	               chunk.append(" "+word); chunkTags++;
    	              	 }
    	              	 newState = NOUN;
                     }
                     else {
       	               newState = STOP;
         	             // EXPERIMENT
         	             // offset = startPos-tokenArray[startTagNo].length(); tokenNo = startTagNo; embedStartTagNo = -1;
    	              	 startPos = offset; startTagNo = tokenNo;
      	               chunk.setLength(0); chunkTags =0;
                     }
                     break;
    	  case(VERB) : if (pos.equals("NN") || pos.equals("NNS")) {
                       chunk.append(" "+word); chunkTags++;
       	               newState = NOUN;
                     }    	               
    	               else if (pos.equals("JJ")) {
                       chunk.append(" "+word); chunkTags++;
    	              	 newState = ADJ;
                     } 
    	               else if (pos.equals("DT")|| pos.startsWith("VB") || pos.equals("IN") || pos.equals("''") || pos.equals(",") || pos.startsWith("PRP")|| pos.equals("CD") || pos.equals(")") || pos.equals("(")|| pos.equals("TO")) { 
                       chunk.setLength(0); chunkTags =0;
    	              	 startPos = offset; startTagNo = tokenNo;
    	              	 newState = DT;
                     }
    	               else {
                       chunk.setLength(0); chunkTags =0;
         	             // EXPERIMENT
         	             // offset = startPos-tokenArray[startTagNo].length(); tokenNo = startTagNo; embedStartTagNo = -1;
    	              	 startPos = offset; startTagNo = tokenNo;
    	              	 newState = STOP;
    	               }
    	  case(NOUN) : if (pos.equals("JJ")) { // should we embed?
       	               // npChunks.add(new String(chunk));
     	              	 // npPositions.add(startPos);
     	              	 addNPC(new String(chunk), startPos, tagArray, tokenArray, startTagNo, chunkTags);
    	  	             chunk.append(" "+word); chunkTags++;
    	  	             embedStartPos = offset; embedStartTagNo = tokenNo; embedPriorTags = chunkTags - 1;
  	              		 embedPoint = chunk.length();
    	              	 newState = ADJ;
    	               }
    	               else if (pos.equals("CC") || pos.equals("TO")) { // What about RB & RP - e.g. "[the] start up [!DT !PRP]"
    	              	 addNPC(new String(chunk), startPos, tagArray, tokenArray, startTagNo, chunkTags);
    	              	 chunk.append(" "+word); chunkTags++;
    	              	 newState = CC;
                     }
    	               else if (pos.equals("DT")|| pos.startsWith("VB") || pos.equals("IN") || pos.equals("''") || pos.equals(",") || pos.startsWith("PRP")|| pos.equals("CD") || pos.equals(")") || pos.equals("(")|| pos.equals("TO")) { // embed adds 200 fa
    	              	 addNPC(new String(chunk), startPos, tagArray, tokenArray, startTagNo, chunkTags);
    	              	 if (embedStartTagNo > startTagNo) {
    	              		 String embed = new String(chunk.substring(embedPoint));
    	              		 addNPC(embed, embedStartPos, tagArray, tokenArray, embedStartTagNo, chunkTags-embedPriorTags);
    	              	 }
    	              	 chunk.setLength(0); chunkTags =0;
    	              	 startPos = offset; startTagNo = tokenNo; embedStartTagNo = -1;
    	              	 newState = DT;
                     }
                     else if (pos.equals("NN") || pos.equals("NNS")) {
                       chunk.append(" "+word); chunkTags++;
    	              	 newState = NOUN;
                     }
    	               else { // add chunk
    	              	 addNPC(new String(chunk), startPos, tagArray, tokenArray, startTagNo, chunkTags);
    	              	 if (embedStartTagNo > startTagNo) {
    	              		 String embed = new String(chunk.substring(embedPoint));
    	              		 addNPC(embed, embedStartPos, tagArray, tokenArray, embedStartTagNo, chunkTags-embedPriorTags);
    	              	 }
    	              	 chunk.setLength(0); chunkTags =0;
    	              	 startPos = offset; startTagNo = tokenNo; // added
    	              	 newState = STOP;
    	               }
    	               break;
    	  case(ADJ)  : if (pos.equals("NN") || pos.equals("NNS")) {
       	               if (chunk.length()==0) {
       	              	 startPos = offset; startTagNo = tokenNo;
    	              		 chunk.append (word); chunkTags = 1;
    	              	 }
    	              	 else chunk.append(" "+word); chunkTags++;
    	              	 newState = NOUN;
                     }
    	               else if (pos.equals("JJ")) {
    	              	 if (chunk.length()==0) {
    	              		 startPos = offset; startTagNo = tokenNo;
    	              		 chunk.append (word); chunkTags = 1;
    	              	 }
    	              	 else chunk.append(" "+word); chunkTags++;
    	              	 newState = ADJ;
                     } 
    	               else if (pos.equals("DT")|| pos.startsWith("VB") || pos.equals("IN") || pos.equals("''") || pos.equals(",") || pos.startsWith("PRP")|| pos.equals("CD") || pos.equals(")") || pos.equals("(")|| pos.equals("TO")) { 
                       chunk.setLength(0); chunkTags =0;
    	              	 startPos = offset; startTagNo = tokenNo;
    	              	 newState = DT;
                     }
                     else if (pos.startsWith("VB")) { // makes no noticeable difference
                       chunk.setLength(0); chunkTags =0;
         	             chunk.append(word); chunkTags++;
         	             startPos = offset; startTagNo = tokenNo;
         	             newState = VERB;
                     }
    	               else {
                       chunk.setLength(0); chunkTags =0;
         	             // EXPERIMENT
         	             // offset = startPos-tokenArray[startTagNo].length(); tokenNo = startTagNo; embedStartTagNo = -1;
    	              	 startPos = offset; startTagNo = tokenNo;
    	              	 newState = STOP;
    	               }
    	  } // end switch
    	  lastWord = word.toLowerCase();
    	  lastState = newState;
    	  lastTag = pos;
    	} // end while loop
    
    // now extract verb phrase chunks - Q do we want to distinguish passive/active and therefore obj, subj?
    tagList = new StringTokenizer(tags, " ");
		tokenList = new StringTokenizer(tokens, " ");
		lastState = 0;
		newState = 0;
		chunk.setLength(0); chunkTags =0;
		if (posPositions!=null) {
			posIt = posPositions.iterator();
		}

		offset = 0;
/*		while (tokenList.hasMoreTokens()) {
    	String pos = tagList.nextToken();
    	String word = tokenList.nextToken();*/
    for (int tokenNo=0;tokenNo<tokenTotal;++tokenNo) {
      String pos = tagArray[tokenNo];
      String word = tokenArray[tokenNo];
    	String wordLow = word.toLowerCase();
    	offset += tokenString.substring(offset).indexOf(word);
      if (posIt!=null) {
      	if (posIt.hasNext()) {
      		offset = posIt.next();
      	}
      }
      if (pos.equals("''")) {
      	//skip
      	if (chunk.length() > 0) chunk.append(" "+word); chunkTags++;
      }
      else if (lastState == STOP && pos.startsWith("VB")) {
    		chunk.append(word); chunkTags++;
    		priorTag = lastTag;
    		startPos = offset; startTagNo = tokenNo;
      	newState = VERB;
    	}
    	else if (lastState == VERB && pos.startsWith("VB")) { // this could be am embed though, e.g. "make do" - strictly an lvc
        tempString = new String (chunk.toString()+" "+word); embedChunkTags = chunkTags+1; embedStartTagNo = startTagNo; 
    		chunk.setLength(0); chunkTags = 0;
    		chunk.append(word); chunkTags++;
    		priorTag = lastTag;
    		startPos = offset; startTagNo = tokenNo;
    	}
// what about CC? as in "coming and going" or "working like a dog" 
    	else if (lastState == VERB && (pos.equals("RP") || pos.equals("TO") || pos.equals("IN") || pos.equals("RB"))) {
    		if (tempString.contains(" ")) {
          if (!defeatLVC(tempString) && (priorTag.equals("IN")||priorTag.equals("TO"))) { 
         		addVPC(tempString, startPos, tagArray, tokenArray, embedStartTagNo, embedChunkTags); // something not quite right...
      		  //vpcChunks.add (tempString); vpcVectors.add("temp");
      		  //vpcPositions.add(startPos);
          }
    			tempString="";
    		}
    		chunk.append(" "+word); chunkTags++;
      	if (pos.equals("TO")) newState = TO;
    		if (pos.equals("RP")) newState = RP;
    		if (pos.equals("RB")) newState = RB;
    		if (pos.equals("IN")) newState = IN;
      }
      else if (lastState == VERB && pos.equals("PRP")) {
    		chunk.append(" "+word); chunkTags++;
      	newState = VBPRP;
      }
      else if (lastState == VERB && (wordLow.equals("a") || wordLow.equals("the") || (allLVCmode) && pos.equals("DT"))) { // avoid relative determiners - though relative could be lvc...
    		chunk.append(" "+word); chunkTags++;
      	newState = VBDT;
      }

      // VB NN or VB JJ NN will get some valid LVCs but at great expense!
      else if ((lastState == VERB) && pos.startsWith("NN") && allLVCmode) {
    		chunk.append(" "+word); chunkTags++;
        newState = NOUN;
    	}
    	else if ((lastState == VERB) && pos.startsWith("JJ")  && allLVCmode) {
    		chunk.append(" "+word); chunkTags++;
        newState = NOUN;
    	}

    	else if (lastState == VERB) {
    		chunk.setLength(0); chunkTags =0;
    		newState = STOP;
    	}
      else if (lastState == TO) {
       	if ((pos.equals(",") || pos.equals(".")|| pos.equals("RP")|| pos.startsWith("TO") || pos.equals("CC"))) { 
       		addVPC(new String(chunk), startPos, tagArray, tokenArray, startTagNo, chunkTags);
       		//vpcChunks.add (new String(chunk));
      		//vpcPositions.add(startPos);
      		chunk.setLength(0); chunkTags =0;
      		newState = STOP;
         }
   	    else if (pos.equals("DT")) {
  	    	chunk.append (" "+word); chunkTags++;
  	    	newState = DT;
  	     }
  	    else if (pos.startsWith("PRP")) {
       		addVPC(new String(chunk), startPos, tagArray, tokenArray, startTagNo, chunkTags);
	      	//vpcChunks.add (new String(chunk));
	      	//vpcPositions.add(startPos);
  	    	chunk.append (" "+word); chunkTags++;
  	    	newState = PRP;
  	     }
      	else if (pos.startsWith("VB")) {
      		chunk.setLength(0); chunkTags =0;
      		chunk.append(word); chunkTags++;
      		priorTag = lastTag;
      		startPos = offset; startTagNo = tokenNo;
        	newState = VERB;
      	}
      	else {
      		chunk.setLength(0); chunkTags =0;
      		newState = STOP;
      	}
   	  }
      else if (lastState == RP) {
      	// dt picks up a lot of FA, as does prp, cd doesnt get much nn gets many fa TO picks up some correct (11) + a few fa (4)
      	     if ( pos.equals(",") || pos.equals(".") || pos.equals("IN") || pos.startsWith("CC") || pos.equals("TO") || pos.equals("RP") || pos.equals("''")) { 
               if (chunk.length() > 0) {
              		addVPC(new String(chunk), startPos, tagArray, tokenArray, startTagNo, chunkTags);
      	      		//vpcChunks.add (new String(chunk));
      	      		//vpcPositions.add(startPos);
      	      		chunk.setLength(0); chunkTags =0;
      	      		newState = STOP;
      	         }
      	      	else {
      	      		chunk.setLength(0); chunkTags =0;
      	      		newState = STOP;
      	      	}
      	   	  }
       	    else if (pos.equals("DT")) { // vpc gets 26 but 3 fa
           		addVPC(new String(chunk), startPos, tagArray, tokenArray, startTagNo, chunkTags);
   	      		//vpcChunks.add (new String(chunk));
  	      		//vpcPositions.add(startPos);
      	    	chunk.append (" "+word); chunkTags++;
      	    	newState = DT;
      	     }
      	    else if (pos.startsWith("PRP")) { // 8 correct no fa
           		addVPC(new String(chunk), startPos, tagArray, tokenArray, startTagNo, chunkTags);
    	      	//vpcChunks.add (new String(chunk));
    	      	//vpcPositions.add(startPos);
      	    	chunk.append (" "+word); chunkTags++;
      	    	newState = PRP;
      	    }
          	else if (pos.startsWith("VB")) {
          		chunk.setLength(0); chunkTags =0;
          		startPos = offset; startTagNo = tokenNo;
          		chunk.append(word); chunkTags++;
          		priorTag = lastTag;
          		newState = VERB;
          	}
      	    else {
    	      	chunk.setLength(0); chunkTags =0;
    	      	newState = STOP;
      	   	}
        }
      else if (lastState == RB) {
      	     if ( pos.equals(",") || pos.equals(".") || pos.startsWith("CC") || pos.equals("TO") || pos.equals("RP") || pos.equals("''")) { 
               if (chunk.length() > 0) {
              	 if (pos.equals("RP")) {
           	    	 chunk.append (" "+word); chunkTags++;
              	  }
              		addVPC(new String(chunk), startPos, tagArray, tokenArray, startTagNo, chunkTags);
      	      		//vpcChunks.add (new String(chunk));
      	      		//vpcPositions.add(startPos);
      	      		chunk.setLength(0); chunkTags =0;
      	      		newState = STOP;
      	         }
      	      	else {
      	      		chunk.setLength(0); chunkTags =0;
      	      		newState = STOP;
      	      	}
      	   	  }
       	    else if (pos.equals("DT")) { // vpc gets 26 but 3 fa
      	    	chunk.append (" "+word); chunkTags++;
      	    	newState = DT;
      	     }
      	    else if (pos.startsWith("PRP")) { // correct  fa
      	    	chunk.append (" "+word); chunkTags++;
      	    	newState = PRP;
      	    }
          	else if (pos.startsWith("VB")) {
          		chunk.setLength(0); chunkTags =0;
          		startPos = offset; startTagNo = tokenNo;
          		chunk.append(word); chunkTags++;
          		priorTag = lastTag;
          		newState = VERB;
          	}
      	    else {
    	      	chunk.setLength(0); chunkTags =0;
    	      	newState = STOP;
      	   	}
        }
      else if (lastState == IN) {
      	     if ((pos.equals(",") || pos.equals(".") || pos.startsWith("CC") || pos.startsWith("TO") || pos.startsWith("IN") || pos.equals("RP") || pos.equals("''"))) { // prp may need additional step... || pos.equals("PRP")
      	        if (chunk.length() > 0) {
      	       		addVPC(new String(chunk), startPos, tagArray, tokenArray, startTagNo, chunkTags);
      	      		//vpcChunks.add (new String(chunk));
      	      		//vpcPositions.add(startPos);
      	      		chunk.setLength(0); chunkTags =0;
      	      		newState = STOP;
      	         }
      	      	else {
      	      		chunk.setLength(0); chunkTags =0;
      	      		newState = STOP;
      	      	}
      	   	  }
        	  else if (pos.equals("DT")) { 
        	    chunk.append (" "+word); chunkTags++;
        	    newState = DT;
        	    }
      	    else if (pos.startsWith("PRP")) { // 3 , 223 fa, if NN closure 198 fa
      	    	tempString = new String(chunk);
              embedChunkTags = chunkTags; embedStartTagNo = startTagNo; 
      	    	chunk.append (" "+word); chunkTags++;
      	    	newState = PRP;
      	    }
          	else if (pos.startsWith("VB")) {
          		chunk.setLength(0); chunkTags =0;
          		startPos = offset; startTagNo = tokenNo;
          		chunk.append(word); chunkTags++;
          		priorTag = lastTag;
          		newState = VERB;
          	}
   	      	else {
  	      		chunk.setLength(0); chunkTags =0;
  	      		newState = STOP;
  	      	}
        }
      else if (lastState == DT) { // this to get some LVCs (6) but won't pick up compound noun.
      	if (pos.startsWith("NN")) {
      		chunk.append(" "+word); chunkTags++;
      		newState = NOUN;
      	}
      	else if (pos.equals(".") || pos.equals(",") || pos.equals("CC")) {
       		addVPC(new String(chunk), startPos, tagArray, tokenArray, startTagNo, chunkTags);
      		//vpcChunks.add (new String(chunk));
      		//vpcPositions.add(startPos);
      		chunk.setLength(0); chunkTags =0;
          newState = STOP;
      	}
      	else if (pos.startsWith("VB")) {
      		chunk.setLength(0); chunkTags =0;
      		startPos = offset; startTagNo = tokenNo;
      		chunk.append(word); chunkTags++;
      		priorTag = lastTag;
      		newState = VERB;
      	}
      	else {
      		chunk.setLength(0); chunkTags =0;
      	  newState = STOP;
      	}
      }
      else if (lastState == PRP) { // this to get some LVCs but won't pick up compound noun.
      	if (pos.startsWith("NN")) {
      		chunk.append(" "+word); chunkTags++;
       		addVPC(tempString, startPos, tagArray, tokenArray, embedStartTagNo, embedChunkTags); 
	      	//vpcChunks.add (tempString); vpcVectors.add("temp");
      		//vpcPositions.add(startPos);
          newState = NOUN;
      	}
      	else if (pos.equals(".") || pos.equals(",") || pos.equals("CC")) {
       		addVPC(tempString, startPos, tagArray, tokenArray, embedStartTagNo, embedChunkTags); 
       		//addVPC(new String(chunk), startPos, tagArray, tokenArray, startTagNo, chunkTags);
      		//vpcChunks.add (new String(chunk));
      		//vpcPositions.add(startPos);
      		chunk.setLength(0); chunkTags =0;
        	newState = STOP;
      	}
      	else if (pos.startsWith("VB")) {
      		chunk.setLength(0); chunkTags =0;
      		startPos = offset; startTagNo = tokenNo;
      		chunk.append(word); chunkTags++;
      		priorTag = lastTag;
      		newState = VERB;
      	}
      	else {
       		chunk.setLength(0); chunkTags =0;
        	newState = STOP;
      	}
      }
 
      // Experimental. Only call following verb.
      else if (lastState == CC) {
      	if (pos.startsWith("VB")) { // this may need to be defeated if 2nd verb is actually VPC or LVC itself
      		chunk.append(" "+word); chunkTags++;
          if (!defeatLVC(chunk.toString())) {
       		  addLVC(new String(chunk), startPos, tagArray, tokenArray, startTagNo, chunkTags);
      		//lvcChunks.add (new String(chunk));
      		//lvcPositions.add(startPos);
          }
      		chunk.setLength(0); chunkTags =0;
      		startPos = offset; startTagNo = tokenNo;
      		chunk.append(word); chunkTags++;
      		priorTag = lastTag;
      		newState = VERB;
      	}
      }

      else if (lastState == VBDT) { // possibly in LVC
      	if (pos.startsWith("NN")) {
      		chunk.append(" "+word); chunkTags++;
          newState = NOUN;
      	}
      	else if (pos.startsWith("JJ")) {
      		chunk.append(" "+word); chunkTags++;
          newState = NOUN;
      	}
      	else if (pos.startsWith("VB")) {
      		chunk.setLength(0); chunkTags =0;
      		startPos = offset; startTagNo = tokenNo;
      		chunk.append(word); chunkTags++;
      		priorTag = lastTag;
      		newState = VERB;
      	}
      	else {
       		chunk.setLength(0); chunkTags =0;
        	newState = STOP;
      	}
      }
      else if (lastState == NOUN) { 
      	if ((pos.equals("IN") || pos.equals("TO")) && (priorTag.equals("TO") || priorTag.startsWith("R") || priorTag.startsWith("NN") || priorTag.startsWith("VB") || priorTag.equals("."))) {// || pos.equals(",") || pos.equals("CC") pos.equals(".")  || 
        //if (!(pos.startsWith("NN")) && (priorTag.equals("TO") || priorTag.startsWith("R") || priorTag.startsWith("NN") || priorTag.startsWith("VB") || priorTag.equals("."))) {// || pos.equals(",") || pos.equals("CC") pos.equals(".")  || 
          if (!defeatLVC(chunk.toString())) {
         		addLVC(new String(chunk), startPos, tagArray, tokenArray, startTagNo, chunkTags);
      		  //lvcChunks.add (new String(chunk));
      		  //lvcPositions.add(startPos);
          }
          chunk.setLength(0); chunkTags =0;
          newState = STOP;
      	}
      	else if (pos.startsWith("NN")) {
      		chunk.append(" "+word); chunkTags++;
      	}
      	else if (pos.startsWith("VB")) {
      		chunk.setLength(0); chunkTags =0;
      		startPos = offset; startTagNo = tokenNo;
      		chunk.append(word); chunkTags++;
      		priorTag = lastTag;
      		newState = VERB;
      	}
      	else {
       		chunk.setLength(0); chunkTags =0;
        	newState = STOP;
      	}
      }
      else if (lastState == VBPRP ) {
      	if (pos.equals("RP")||pos.equals("RB")) {// IN gets too many fas
      		chunk.append(" "+word); chunkTags++;
      		addVPC(new String(chunk), startPos, tagArray, tokenArray, startTagNo, chunkTags);
    		  //vpcChunks.add (new String(chunk));
    		  //vpcPositions.add(startPos);
       		chunk.setLength(0); chunkTags =0;
        	newState = STOP;
      	}
      	else if (pos.startsWith("VB")) {
      		chunk.setLength(0); chunkTags =0;
      		startPos = offset; startTagNo = tokenNo;
      		chunk.append(word); chunkTags++;
      		priorTag = lastTag;
      		newState = VERB;
      	}
      	else {
   		  chunk.setLength(0); chunkTags =0;
      	newState = STOP;
      	}
      }
      lastState = newState;
      lastTag = pos;
      if (pos.equals("PRP")) pronouns.add(new String(word));
    }
  filterVPCs();
	}

	public LinkedList<String> getNounChunks () {
		return npChunks;
	}
	
	public LinkedList<Integer> getNounChunkStarts () {
		return npPositions;
	}
	
	public LinkedList<Integer> getNounChunkEnds () {
		return npEndpoints;
	}
	
	public LinkedList<String> getNounMWEs () {
		LinkedList<String> mwes = new LinkedList<String>();
	  Iterator<String> it = npChunks.iterator();
    while (it.hasNext()) {
		  String np = (String) it.next();
	    if (np.contains(" ")) mwes.add(np);
    }
		return mwes;
	}
	
	public LinkedList<Integer> getNounMWEstarts () {
		LinkedList<Integer> mwes = new LinkedList<Integer>();
	  Iterator<Integer> pit = npPositions.iterator();
	  Iterator<String> it = npChunks.iterator();
    while (it.hasNext()) {
		  String np = (String) it.next();
		  int position = pit.next();
	    if (np.contains(" ")) mwes.add(position);
    }
		return mwes;
	}
	
	public LinkedList<String[]> getNounMWEtags () {
		LinkedList<String[]> mwes = new LinkedList<String[]>();
	  Iterator<String[]> tit = npTags.iterator();
	  Iterator<String> it = npChunks.iterator();
    while (it.hasNext()) {
		  String np = (String) it.next();
		  String[] vec = tit.next();
	    if (np.contains(" ")) mwes.add(vec);
    }
		return mwes;
	}
	
	public LinkedList<String> getNounMWEvectors () {
		LinkedList<String> mwes = new LinkedList<String>();
	  Iterator<String> vit = npVectors.iterator();
	  Iterator<String> it = npChunks.iterator();
    while (it.hasNext()) {
		  String np = (String) it.next();
		  String vec = vit.next();
	    if (np.contains(" ")) mwes.add(vec);
    }
		return mwes;
	}
	
	public LinkedList<String> getVerbMWEs () {
		LinkedList<String> mwes = new LinkedList<String>();
	  Iterator<String> it = vpcChunks.iterator();
    while (it.hasNext()) {
		  String vpc = (String) it.next();
	    if (vpc.contains(" ")) mwes.add(vpc);
    }
		return mwes;
	}
	
	public LinkedList<String> getPhraseMWEs () {
		LinkedList<String> mwes = new LinkedList<String>();
	  Iterator<String> it = lvcChunks.iterator();
    while (it.hasNext()) {
		  String vpc = (String) it.next();
	    if (vpc.contains(" ")) mwes.add(vpc);
    }
		return mwes;
	}
	
	public LinkedList<String> getVPCVectors () {
		return vpcVectors;
	}
	
	public LinkedList<String> getVPCChunks () {
		return vpcChunks;
	}
	
	public LinkedList<String[]> getVPCTags () {
		return vpcTags;
	}
	
	public LinkedList<Integer> getVPCChunkStarts () {
		return vpcPositions;
	}
	
	public LinkedList<Integer> getVPCChunkEnds () {
		return vpcEndpoints;
	}
	
	public LinkedList<String> getLVCVectors () {
		return lvcVectors;
	}
	
	public LinkedList<String> getLVCChunks () {
		return lvcChunks;
	}
	
	public LinkedList<String[]> getLVCTags () {
		return lvcTags;
	}
	
	public LinkedList<Integer> getLVCChunkStarts () {
		return lvcPositions;
	}
	
	public LinkedList<Integer> getLVCChunkEnds () {
		return lvcEndpoints;
	}
	
	public LinkedList<String> getPRPs () {
		return pronouns;
	}
	
  public String toString() {
	  Iterator<String> it = npChunks.iterator();
	  StringBuffer sb = new StringBuffer();
	  sb.append("{");
    while (it.hasNext()) {
  	  sb.append(it.next()+",");
      }
    sb.append("}");
    
	  it = vpcChunks.iterator();
	  sb.append(",{");
    while (it.hasNext()) {
  	  sb.append(it.next()+",");
      }
    sb.append("}");
    return sb.toString();
  }

/*
 * getTagFile reads the specified file for a tag sequence in the form of "[DT][NN][VB][...."
 * Newlines, spaces, and square brackets are treated as delimiters, everything else is considered a tag
 * Tags should conform to Stanford POS tag set.
 */
  
  private String getTagFile (String inputFilename) {
    //System.err.println("Reading: "+inputFilename);
		FileReader docFileR;
		try {
			docFileR = new FileReader(inputFilename);
		} catch (FileNotFoundException e1) {
			// TODO Auto-generated catch block
			System.err.println ("File not found: "+inputFilename);
			return null;
		}
		StringBuffer buff = new StringBuffer();
		char c;
		
    try {
			int ch = docFileR.read();
	    if (ch==-1) {
	    	docFileR.close();
	    	return null;
	    }
	    boolean lastSpace = false;
			while (ch != -1) {
				c = (char) ch;
				if (c=='\r' || c=='[' || c== ']') {
					c= ' ';
					if (!lastSpace) {
						buff.append(c);
						lastSpace = true;
					}
				} else { 
				  lastSpace = false;
					buff.append (c);
				}
				ch = docFileR.read();
			}
			docFileR.close();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
		return buff.toString();
	}

/*
 * getTokenFile reads the file specified by inputFilename and assumes the contents are a sequence of space-delimited tokens
 */
  
  private String getTokenFile (String inputFilename) {
		FileReader docFileR;
		try {
			docFileR = new FileReader(inputFilename);
		} catch (FileNotFoundException e1) {
			// TODO Auto-generated catch block
			System.err.println ("File not found: "+inputFilename);
			return null;
		}
		StringBuffer buff = new StringBuffer();
		char c;
		
    try {
			int ch = docFileR.read();
	    if (ch==-1) { 
	    	docFileR.close();
	    	return null;
	    }
			while (ch != -1) {
				c = (char) ch;
			  buff.append (c);
				ch = docFileR.read();
			}
			docFileR.close();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
		return buff.toString();
	}

  /*
   * chunkTagAndTokenFiles reads the specified tagFile for a tag sequence in the form of "[DT][NN][VB][...."
   * Newlines, spaces, and square brackets are treated as delimiters, everything else is considered a tag
   * Tags should conform to Stanford POS tag set.
   * It reads the file tokenFile for the associated space or newline delimited tokens
   */
    
  public void chunkTagAndTokenFiles (String tagFile, String tokenFile) throws Exception {
  	String tags;
  	String tokens;
  	if ((tags=getTagFile(tagFile))==null) throw new Exception();
  	if ((tokens=getTokenFile(tokenFile))==null) throw new Exception();
  	chunk(tags, tokens);
  }
  
	public static void main(String args[]) {
		if (args.length < 2) {
			System.err.println("No specified files");
			System.err.println("Usage: MweTester tokenFile tagFile");
			System.exit(-1);
		}
		MweChunker chunker = new MweChunker();
		try {
		  chunker.chunkTagAndTokenFiles(args[0],args[1]);
		} catch (Exception e) {
			e.printStackTrace();
		}
		System.out.println(chunker.toString());
	}

}
	

