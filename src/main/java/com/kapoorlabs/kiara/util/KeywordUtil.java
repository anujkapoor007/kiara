package com.kapoorlabs.kiara.util;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.PriorityBlockingQueue;

import com.kapoorlabs.kiara.domain.Condition;
import com.kapoorlabs.kiara.domain.KeywordConditionsPair;
import com.kapoorlabs.kiara.domain.KeywordSearchResult;
import com.kapoorlabs.kiara.domain.MatchesForKeyword;
import com.kapoorlabs.kiara.domain.Operator;
import com.kapoorlabs.kiara.domain.Store;
import com.kapoorlabs.kiara.search.StoreSearch;

import opennlp.tools.stemmer.snowball.SnowballStemmer;

public class KeywordUtil {

    private KeywordSearchResult result;

    private int bestPossibleMatchScore;

    private Stack<KeywordConditionsPair>[] queryStack;

    private long[] processedQueryCount;

    private long[] maxQueryCount;

    private long[] factorialMap;

    private ArrayList<MatchesForKeyword> getMatchesForKeyword(Set<String> keywords, Store store) {

        ArrayList<MatchesForKeyword> matchesForKeywords = new ArrayList<>();

        for (String keyword : keywords) {

            keyword = keyword.trim().toUpperCase();

            MatchesForKeyword matchesForKeyword = new MatchesForKeyword(keyword);

            for (int i = 0; i < store.getInvertedIndex().size(); i++) {

            	if (store.getSdqlColumns()[i].isStemmedIndex()) {
            		
            		SnowballStemmer stemmer = new SnowballStemmer(SnowballStemmer.ALGORITHM.ENGLISH);
					String stemmedKey = stemmer.stem(keyword.toLowerCase()).toString().toUpperCase();
            		
                    if (store.getInvertedIndex().get(i).containsKey(stemmedKey)) {
                        matchesForKeyword.getColMatches().add(i);
                        matchesForKeyword.setKeyword(stemmedKey);
                    }
            		
            	} else {
            		
                    if (store.getInvertedIndex().get(i).containsKey(keyword)) {
                        matchesForKeyword.getColMatches().add(i);
                    }
            		
            	}
            }

            if (!matchesForKeyword.getColMatches().isEmpty()) {
                matchesForKeywords.add(matchesForKeyword);
            } else {
                String autoCorrectedWord = SpellCheckUtil.getOneEditKeyword(keyword, store.getSpellCheckTrie());
                if (autoCorrectedWord != null) {

                    matchesForKeyword.setKeyword(autoCorrectedWord);

                    for (int i = 0; i < store.getInvertedIndex().size(); i++) {

                        if (store.getInvertedIndex().get(i).containsKey(autoCorrectedWord)) {
                            matchesForKeyword.getColMatches().add(i);
                        }
                    }

                    if (!matchesForKeyword.getColMatches().isEmpty()) {
                        matchesForKeywords.add(matchesForKeyword);
                    }

                }
            }

        }

        return matchesForKeywords;
    }


    public KeywordSearchResult getBestMatch(String sentence, Store store,
                                            int keywordPos, HashMap<Integer, Set<String>> combination) {

        KeywordSearchResult keywordSearchResult = new KeywordSearchResult(new HashSet<>(), new LinkedList<>());
        sentence = SpellCheckUtil.removeStopWords(sentence).toUpperCase();
        Set<String> keywords = new HashSet<>(Arrays.asList(sentence.split(" ")));
        ArrayList<MatchesForKeyword> matchesForKeywords = getMatchesForKeyword(keywords, store);

        int totalKeywords = matchesForKeywords.size();

        if (totalKeywords == 0) {
            return keywordSearchResult;
        }

        bestPossibleMatchScore = totalKeywords;

        queryStack = new Stack[totalKeywords];
        processedQueryCount = new long[totalKeywords];
        maxQueryCount = new long[totalKeywords];
        generateFactorialMap(totalKeywords);


        getBestMatchHelper(matchesForKeywords, store, keywordPos, combination, new HashSet<>());

        if (result != null) {
            keywordSearchResult = result;
        } else {
            for (int i = bestPossibleMatchScore; i >= 1; i--) {
                processQueryStack(i - 1, store);
                if (result != null) {
                    keywordSearchResult = result;
                    break;
                }
            }
        }

        return keywordSearchResult;

    }

    private void getBestMatchHelper(ArrayList<MatchesForKeyword> matchesForKeywords, Store store,
                                    int keywordPos, HashMap<Integer, Set<String>> combination, Set<String> keywords) {

        if (result != null) {
            return;
        }

        if (keywordPos == matchesForKeywords.size()) {

            Set<String> keywordClone = new HashSet<>(keywords);
            KeywordConditionsPair keywordConditionsPair = new KeywordConditionsPair(keywordClone, formConditions(store, combination));

            if (queryStack[keywordClone.size() - 1] == null) {
                queryStack[keywordClone.size() - 1] = new Stack<>();
            }

            queryStack[keywordClone.size() - 1].push(keywordConditionsPair);
            processedQueryCount[keywordClone.size() - 1]++;

            if (keywordClone.size() == bestPossibleMatchScore) {
                processQueryStack(bestPossibleMatchScore - 1, store);
                long maxQueryNumber = maxQueryCount[bestPossibleMatchScore - 1];

                if (maxQueryNumber == 0) {
                    maxQueryNumber = getMaxQueryCount(matchesForKeywords, queryStack.length, bestPossibleMatchScore);
                    maxQueryCount[bestPossibleMatchScore - 1] = maxQueryNumber;
                }
                if (processedQueryCount[bestPossibleMatchScore - 1] == maxQueryNumber) {
                    bestPossibleMatchScore--;
                }
            }

            return;

        }

        for (int i = 0; i < matchesForKeywords.get(keywordPos).getColMatches().size(); i++) {

            if (result != null) {
                return;
            }

            if (!combination.containsKey(matchesForKeywords.get(keywordPos).getColMatches().get(i))) {
                combination.put(matchesForKeywords.get(keywordPos).getColMatches().get(i), new HashSet<>());
            }

            combination.get(matchesForKeywords.get(keywordPos).getColMatches().get(i))
                    .add(matchesForKeywords.get(keywordPos).getKeyword());

            keywords.add(matchesForKeywords.get(keywordPos).getKeyword());

            getBestMatchHelper(matchesForKeywords, store, keywordPos + 1, combination, keywords);

            if (combination.get(matchesForKeywords.get(keywordPos).getColMatches().get(i)).size() == 1) {
                combination.remove(matchesForKeywords.get(keywordPos).getColMatches().get(i));
            } else {
                combination.get(matchesForKeywords.get(keywordPos).getColMatches().get(i))
                        .remove(matchesForKeywords.get(keywordPos).getKeyword());
            }
            keywords.remove(matchesForKeywords.get(keywordPos).getKeyword());

        }

        getBestMatchHelper(matchesForKeywords, store, keywordPos + 1, combination, keywords);

    }

    private static List<Condition> formConditions(Store store, HashMap<Integer, Set<String>> combination) {

        List<Condition> formedConditions = new LinkedList<Condition>();

        for (Integer key : combination.keySet()) {

            formedConditions.add(new Condition(store.getSdqlColumns()[key].getColumnName(), Operator.CONTAINS_ALL, combination.get(key)));

        }

        return formedConditions;

    }

    private void processQueryStack(int index, Store store) {

        Stack<KeywordConditionsPair> stack = queryStack[index];

        StoreSearch storeSearch = new StoreSearch();

        while (!stack.isEmpty()) {
            KeywordConditionsPair keywordConditionsPair = stack.pop();
            List<Map<String, String>> results = storeSearch.query(store, keywordConditionsPair.getConditions(), null);
            if (results.size() > 0) {
                result = new KeywordSearchResult(keywordConditionsPair.getKeywords(), results);
                break;
            }
        }
    }

    private void generateFactorialMap(int n) {
        factorialMap = new long[n + 1];
        long fact = 1;
        factorialMap[0] = 1;
        for (int i = 1; i <= n; i++) {
            fact = fact * i;
            factorialMap[i] = fact;
        }
    }

    private long getMaxQueryCount(int n, int r) {
        return factorialMap[n] / (factorialMap[r] * factorialMap[n - r]);
    }

    private long getMaxQueryCount(ArrayList<MatchesForKeyword> matchesForKeywords, int n, int r) {
        long baseCount = getMaxQueryCount(n, r);

        for (MatchesForKeyword matchesForKeyword: matchesForKeywords) {
            if (matchesForKeyword.getColMatches().size() > 1) {
                baseCount += (matchesForKeyword.getColMatches().size() -1) * getMaxQueryCount(n-1, r-1);
            }
        }
        return baseCount;
    }

}
