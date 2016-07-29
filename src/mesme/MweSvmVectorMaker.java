package mesme;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.tartarus.snowball.ext.englishStemmer;

public class MweSvmVectorMaker {

	// Now need a class to create a valid SVM input vector representation of a categorical feature sequence
	// Need a set of categories for each feature, one dim for each dependent and repet process for each independent set of categories. Repeat for each position.
	// POS set, ORTHOGRAPHIC set, how to do lexical?
	// Possibly should make this own class
	
  private HashMap<String,Integer> posTagDims = new HashMap<String,Integer>();
  private HashMap<String,Integer> orthoDims = new HashMap<String,Integer>();
  private HashMap<String,Integer> tokenIndex = new HashMap<String,Integer>();
  private int posDimsPerToken;
  private int orthoDimsPerToken;
  private int tokensIndexed = 0;
  private Set<String> tokenTagFilter = new HashSet<String>();
  private int featuresPerToken;
  private int maxTokens = 20;
	private englishStemmer stemmer = new englishStemmer();
  
  private void initHashSet (HashMap<String,Integer> hs, Set<String> ss) {
		Iterator<String> it = ss.iterator();
		int i = 0;
		hs.put ("UNK", 0);
		++i;
		while (it.hasNext()) {
			String tag = it.next();
			if (!it.equals("UNK")) {
			  hs.put(tag, i);
			  i++;
			}
		}
  }
  
  public MweSvmVectorMaker (HashSet<String> tagSet) {
    initHashSet (posTagDims, tagSet);
    posDimsPerToken = posTagDims.size();
    featuresPerToken = posDimsPerToken;
  }
  
  public MweSvmVectorMaker (HashSet<String> tagSet, Set<String> orthoSet) {
    initHashSet (posTagDims, tagSet);
    posDimsPerToken = posTagDims.size();
    initHashSet (orthoDims, orthoSet);
    orthoDimsPerToken = orthoDims.size();
    featuresPerToken = posDimsPerToken + orthoDimsPerToken;
  }
  
  public void setFeatureTokensByType (Set<String> filtTagSet) {
  	tokenTagFilter = filtTagSet;
  }
  
  public HashMap<String,Integer> getTokenIndex() {
  	return tokenIndex;
  }
  
  private int tokenFeatureNo (String token, String filtTag) {
  	if (!tokenTagFilter.contains(filtTag)) return 0;
  	String lemma = new String(token);
  	stemmer.setCurrent(lemma);
  	stemmer.stem();
  	if (!(tokenIndex.containsKey(lemma))) {
  	// word not seen in previous training - will need active update
  		tokensIndexed++;
  		tokenIndex.put(lemma, tokensIndexed);
  		return tokensIndexed; 
  	}
  	return tokenIndex.get(token);
  }
  
	private int[] tagsVector (String tag) {
		int[] tagsVec = new int[posDimsPerToken];
		for (int i=0;i<posDimsPerToken;++i) {
			tagsVec[i] = 0;
		}
		int fn;
		if (posTagDims.containsKey(tag)) fn = posTagDims.get(tag); else {System.err.println("NO TAG: "+tag);fn=posTagDims.get("UNK");}
		tagsVec[fn] = 1;
		return tagsVec;
	}

	private int[] orthoVector (String tag) {
		int[] tagsVec = new int[orthoDimsPerToken];
		for (int i=0;i<orthoDimsPerToken;++i) {
			tagsVec[i] = 0;
		}
		int fn = orthoDims.get(tag);
		tagsVec[fn] = 1;
		return tagsVec;
	}

	public String svmVector (String[] tags, String[] tokens) {
		StringBuffer svmVec = new StringBuffer();
		
		int tvl = tags.length;
		for (int f=0;f<tvl;++f) {
			int offset = f * featuresPerToken + 1;
		  int[] tvec = tagsVector(tags[f]);
			for (int i=0;i<tvec.length;++i) {
				if (tvec[i]==1) {
			    svmVec.append((i+offset));
			    svmVec.append(':');
			    svmVec.append(tvec[i]);
			    svmVec.append(' ');
				}
		  }
		}
		
		int offset = featuresPerToken * maxTokens;
		for (int f=0;f<tvl;++f) {
      int fn = tokenFeatureNo (tokens[f], tags[f]);
      if (fn >0) {
			  svmVec.append((fn+offset));
			  svmVec.append(":1 ");
      }
		}
		
		return svmVec.toString();
	}

	public String svmVector (String[] tags, String[] tokens, String[] orthographics) {
		StringBuffer svmVec = new StringBuffer();
		
		int tvl = tags.length;
		for (int f=0;f<tvl;++f) {
			int offset = f * featuresPerToken + 1;
		  int[] tvec = tagsVector(tags[f]);
			for (int i=0;i<tvec.length;++i) {
				if (tvec[i]==1) {
			    svmVec.append((i+offset));
			    svmVec.append(':');
			    svmVec.append(tvec[i]);
			    svmVec.append(' ');
				}
		  }
		  int[] ovec = orthoVector (orthographics[f]);
		  offset += posDimsPerToken;
		  for (int i=0;i<ovec.length;++i) {
				if (ovec[i]==1) {
			    svmVec.append((i+offset));
			    svmVec.append(':');
			    svmVec.append(ovec[i]);
			    svmVec.append(' ');
				}
		  }
		}

		int offset = featuresPerToken * maxTokens;
		for (int f=0;f<tokens.length;++f) {
      int fn = tokenFeatureNo (tokens[f], tags[f]);
      if (fn > 0) {
			  svmVec.append((fn+offset));
			  svmVec.append(":1 ");
      }
		}
		
		return svmVec.toString();
	}
}
