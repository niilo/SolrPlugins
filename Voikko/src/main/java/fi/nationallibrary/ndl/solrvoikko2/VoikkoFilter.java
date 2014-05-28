/* 
 * Copyright 2012-2014 The National Library of Finland
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 *********************************************************************************/


package fi.nationallibrary.ndl.solrvoikko2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.puimula.libvoikko.Analysis;
import org.puimula.libvoikko.Voikko;

/**
 * 
 * @author ere.maijala@helsinki.fi
 *
 */
public class VoikkoFilter extends TokenFilter {
  /**
   * The default for minimal word length that gets processed
   */
  public static final int DEFAULT_MIN_WORD_SIZE = 3;

  /**
   * The default for minimal length of subwords that get propagated to the output of this filter
   */
  public static final int DEFAULT_MIN_SUBWORD_SIZE = 2;

  /**
   * The default for maximal length of subwords that get propagated to the output of this filter
   */
  public static final int DEFAULT_MAX_SUBWORD_SIZE = 25;
  
  private static final String BASEFORM_ATTR = "BASEFORM";
  private static final String WORDBASES_ATTR = "WORDBASES";

  protected Voikko voikko;
  protected final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
  protected final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
  protected final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);
  private State current = null;
  private int currentPosition = 1;
  private final boolean expandCompounds;
  private final int minWordSize;
  private final int minSubwordSize;
  private final int maxSubwordSize;
  private final boolean allAnalysis;
  
  private final LinkedHashSet<CompoundToken> tokens;

  private Map<String, List<CompoundToken>> cache;
  
  protected VoikkoFilter(TokenStream input, Voikko voikko, boolean expandCompounds, int minWordSize, int minSubwordSize, int maxSubwordSize, boolean allAnalysis, Map<String, List<CompoundToken>> cache) {
    super(input);
    this.tokens = new LinkedHashSet<CompoundToken>();
    this.voikko = voikko;
    this.expandCompounds = expandCompounds;
    this.minWordSize = minWordSize;
    this.minSubwordSize = minSubwordSize;
    this.maxSubwordSize = maxSubwordSize;
    this.allAnalysis = allAnalysis;
    this.cache = cache;
  }

  @Override
  public final boolean incrementToken() throws IOException {
    if (!tokens.isEmpty()) {
      assert current != null;
      // keep original attributes apart the ones we want to change
      restoreState(current);
      
      CompoundToken token = null;
      int prevPosition = currentPosition;
      // Find next token for this or next position
      for (; currentPosition <= prevPosition + 1; currentPosition++) {
        Iterator<CompoundToken> it = tokens.iterator();
        while (it.hasNext()) {
          CompoundToken t = it.next();
          if (t.position == currentPosition) {
            token = t;
            it.remove();
            break;
          }
        }
        if (token != null) {
          break;
        }
      }
      
      assert token != null;
      if (token == null) {
        tokens.clear();
        return false;
      } 
      termAtt.setEmpty().append(token.txt);
      /*
      It's too complicated trying to get the attributes right especially for
      multi-word strings, so let's keep the original offsets even though we
      only return a part of the original term. It's not that bad, it just causes
      the whole term to be highlighted, but that could be considered better than
      trying to highlight just a part of it (and getting the offsets wrong).
      if (token.startOffset != -1) {
        offsetAtt.setOffset(token.startOffset, token.endOffset);
      }*/
      posIncAtt.setPositionIncrement(currentPosition > prevPosition ? 1 : 0);
      return true;
    }
    
    current = null;
    if (input.incrementToken()) {
      current = captureState();
      String term = termAtt.toString();
      int termLen = term.length();
      if (termLen < minWordSize || !term.matches("[a-zA-ZåäöÅÄÖ]+")) {
        return true;
      }
      List<CompoundToken> cachedTokens = cache != null 
          ? cache.get(term.toLowerCase())
          : null;
      if (cachedTokens != null) {
        tokens.addAll(cachedTokens);
      } else {
        List<Analysis> analysisList = voikko.analyze(term);

        if (analysisList.isEmpty()) {
          ArrayList<CompoundToken> tokenList = new ArrayList<CompoundToken>();
          if (cache != null) {
            cache.put(term.toLowerCase(), tokenList);
          }
          return true;
        }
        
        // Remove duplicates from analysis list
        if (analysisList.size() > 1) {
          LinkedHashSet<Analysis> analysisMap = new LinkedHashSet<Analysis>(analysisList);
          analysisList = new ArrayList<Analysis>(analysisMap);
        }
        
        // Process base forms first
        boolean first = true;
        for (Analysis analysis: analysisList) {
          if (!this.allAnalysis && !first) {
            break;
          }
          if (analysis.containsKey(BASEFORM_ATTR)) {
            String baseform = analysis.get(BASEFORM_ATTR);
            // get rid of equals sign in e.g. di=oksidi
            baseform = baseform.replace("=", "");
            tokens.add(new CompoundToken(baseform, 1));
          }
          first = false;
        }
  
        // Expand compound words
        if (expandCompounds) {
          first = true;
          for (Analysis analysis: analysisList) {
            if (!this.allAnalysis && !first) {
              break;
            }        
            first = false;
            if (!analysis.containsKey(WORDBASES_ATTR)) {
              continue;
            }
            String wordbases = analysis.get(WORDBASES_ATTR);
            
            // Split by plus sign (unless right after an open parenthesis)
            String matches[] = wordbases.split("(?<!\\()\\+");
  
            int currentPos = 0;
            String lastWordBody = "";
            int lastPos = 0;
            int wordPos = 0;
            // The string starts with a plus sign, so skip the first (empty) entry
            for (int i = 1; i <= matches.length - 1; i++) {
              String wordAnalysis = matches[i];
              
              // get rid of equals sign in e.g. di=oksidi
              wordAnalysis = wordAnalysis.replaceAll("=", "");
              
              String wordBody;
              String baseForm;
              int parenPos = wordAnalysis.indexOf('(');
              if (parenPos == -1) {
                wordBody = baseForm = wordAnalysis;
              } else {
                // Word body is before the parenthesis
                wordBody = wordAnalysis.substring(0, parenPos);
                
                // Base form or derivative is in parenthesis
                baseForm = wordAnalysis.substring(parenPos + 1, wordAnalysis.length() - 1);
              }
              
              boolean isDerivative = baseForm.startsWith("+");
  
              String word;
              int wordOffset, wordLen;
              if (isDerivative) {
                // Derivative suffix, merge with word body
                word = lastWordBody + wordBody;
                wordOffset = lastPos;
                wordLen = word.length();
              } else {
                word = baseForm;
                wordOffset = currentPos;
                wordLen = word.length();
                lastWordBody = wordBody;
                lastPos = currentPos;
                currentPos += baseForm.length();
              }
              
              // Make sure we don't exceed the length of the original term
              if (wordOffset + wordLen > termLen) {
                if (wordOffset >= termLen) {
                  wordOffset = wordLen - termLen;
                  if (wordOffset < 0) {
                    wordOffset = 0;
                  }
                } else {
                  wordLen = termLen - wordOffset;
                }
              }
              
              if (wordLen > minSubwordSize) {
                if (wordLen > maxSubwordSize) {
                  word = word.substring(0, maxSubwordSize);
                  wordLen = maxSubwordSize;
                }
                
                if (!isDerivative) {
                  ++wordPos;
                }
                
                tokens.add(new CompoundToken(word, wordPos));
              }
            }
          }
        }
        ArrayList<CompoundToken> tokenList = new ArrayList<CompoundToken>(tokens);
        if (cache != null) {
          cache.put(term.toLowerCase(), tokenList);
        }
      }
      
      currentPosition = 1;
      
      Iterator<CompoundToken> it = tokens.iterator();
      if (it.hasNext()) {
        CompoundToken t = it.next();
        it.remove();
        termAtt.setEmpty().append(t.txt);
      }
      
      return true;
    } 
    return false;
  }

  /**
   * Helper class to hold decompounded token information
   */
  protected class CompoundToken {
    public final CharSequence txt;
    public final int position;

    /** Construct the compound token based on a slice of the current {@link CompoundWordTokenFilterBase#termAtt}. */
    public CompoundToken(CharSequence txt, int position) {
      this.txt = txt;
      this.position = position;
    }
    
    public int hashCode() {
      return this.position;
    }
    
    /**
     * Compare objects
     * 
     * @return boolean
     */
    @Override
    public boolean equals(Object obj) {
      if (obj == null || !(obj instanceof CompoundToken)) {
        return false;
      }
      CompoundToken t2 = (CompoundToken) obj;
      
      return t2.txt.equals(txt) 
          && t2.position == position;
    }

  }  

}