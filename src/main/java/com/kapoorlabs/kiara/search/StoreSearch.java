package com.kapoorlabs.kiara.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeSet;

import com.kapoorlabs.kiara.constants.SdqlConstants;
import com.kapoorlabs.kiara.domain.Condition;
import com.kapoorlabs.kiara.domain.NullableOrderedString;
import com.kapoorlabs.kiara.domain.Operator;
import com.kapoorlabs.kiara.domain.OrderedKeys;
import com.kapoorlabs.kiara.domain.Range;
import com.kapoorlabs.kiara.domain.SdqlNode;
import com.kapoorlabs.kiara.domain.SearchableRange;
import com.kapoorlabs.kiara.domain.Store;
import com.kapoorlabs.kiara.exception.ColumnNotFoundException;
import com.kapoorlabs.kiara.exception.InsufficientDataException;
import com.kapoorlabs.kiara.exception.NonSupportedOperationException;
import com.kapoorlabs.kiara.parser.RangeParser;
import com.kapoorlabs.kiara.util.LogicalUtil;
import com.kapoorlabs.kiara.util.NumericUtil;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class StoreSearch {

	@Getter
	private List<Map<String, String>> result;

	/**
	 * Condition Merger class is used to merge results if more than one condition is
	 * specified on the same column.
	 * 
	 * similarSdqlNodes specifies all the columns that are returned by the query on
	 * the particular column.
	 * 
	 * nextIndex specifies the index in the conditions array, that should be
	 * processed next.
	 * 
	 */

	private class ConditionMerger {
		ArrayList<SdqlNode> similarSdqlNodes;
		int nextIndex;
	}

	public StoreSearch() {
		result = new LinkedList<>();
	}

	/**
	 * This function will search/query the store with provided conditions, and
	 * return the results. The results can be filtered with only the
	 * columns/data-attributes, you want in the result, using the filterSet
	 * argument.
	 * 
	 * @param store      This parameter will pass the store, which will be searched.
	 * 
	 * @param conditions This parameter will pass all the conditions that your
	 *                   result should satisfy.
	 * 
	 * @param filterSet  This parameter will pass a set of columns that should be
	 *                   present in the result, if this parameter is null, all the
	 *                   columns (which are there in the store) will be present in
	 *                   the result.
	 * @result List It returns a list of Map with Key as column Name and Value as
	 *         the value of the column. All values are represented as a String. The
	 *         column names are stored in upper case form. Therefore, each map is a
	 *         individual record, that satisfied the mentioned conditions, and we
	 *         return list of such records.
	 * 
	 * @throws ColumnNotFoundException   This exception is thrown when the column
	 *                                   mentioned in the condition, is not found in
	 *                                   the store.
	 * @throws InsufficientDataException This exception is raised when between
	 *                                   operator is used in a condition, and both
	 *                                   lower and upper ranges are not specified.
	 * @throws NonSupportedException     This exception is raised if any operator
	 *                                   (inside a condition) other than EQUALS
	 *                                   ,CONTAINS_EITHER or CONTAINS_ALL is used in
	 *                                   range based data.
	 * @throws RangeOutOfOrder           This exception is raised when we try to
	 *                                   parse a range with higher range value,
	 *                                   which is less than lower range.
	 * 
	 */
	public List<Map<String, String>> query(Store store, List<Condition> conditions, Set<String> filterSet) {

		if (conditions == null) {
			conditions = new LinkedList<>();
		}

		if (filterSet == null) {
			filterSet = new HashSet<String>();
		}

		TreeSet<Integer> filterColPos = getFilterColPos(filterSet, store);

		Condition[] sortedConditions = sortConditions(store, conditions);

		normalizeNull(store, sortedConditions);

		ArrayList<SdqlNode> prevColumnNodes = new ArrayList<>();

		for (int i = 0; i < sortedConditions.length; i++) {

			ConditionMerger conditionMerger = getNextQualifyingNodes(sortedConditions, i, store);
			if (i == 0) {
				prevColumnNodes = conditionMerger.similarSdqlNodes;
			} else {

				if (conditionMerger.similarSdqlNodes.size() < prevColumnNodes.size()) {
					prevColumnNodes = binarySearchCurrentNodes(prevColumnNodes, conditionMerger.similarSdqlNodes);
				} else {
					prevColumnNodes = inverseBinarySearchCurrentNodes(prevColumnNodes,
							conditionMerger.similarSdqlNodes);
				}

			}

			i = conditionMerger.nextIndex - 1;

		}

		int lastConditionColPos = sortedConditions[sortedConditions.length - 1].getColumnIndex();

		for (SdqlNode resultNode : prevColumnNodes) {
			Map<String, String> resultObject = new HashMap<>();
			if (filterColPos.first() <= lastConditionColPos) {
				buildPreFixMap(resultNode, lastConditionColPos, filterColPos, resultObject, store);
			}

			buildPostfixMap(resultNode, lastConditionColPos + 1, filterColPos, filterColPos.last(), resultObject,
					store);
		}

		return result;
	}

	private ConditionMerger getNextQualifyingNodes(Condition[] conditions, int currentIndex, Store store) {

		ConditionMerger conditionMerger = new ConditionMerger();
		ArrayList<SdqlNode> currentColumnNodes = new ArrayList<>();
		ArrayList<SdqlNode> nextColumnNodes = new ArrayList<>();
		int colIndex = conditions[currentIndex].getColumnIndex();

		for (int i = currentIndex; i < conditions.length; i++) {

			if (i == currentIndex) {
				currentColumnNodes = getQualifyingNodes(conditions[i], store, new ArrayList<>());
				continue;
			}

			if (colIndex != conditions[i].getColumnIndex()) {
				conditionMerger.similarSdqlNodes = currentColumnNodes;
				conditionMerger.nextIndex = i;
				break;
			} else {
				nextColumnNodes = getQualifyingNodes(conditions[i], store, new ArrayList<>());
				currentColumnNodes = getDuplicates(currentColumnNodes, nextColumnNodes);
			}

		}

		if (conditionMerger.similarSdqlNodes == null) {
			conditionMerger.similarSdqlNodes = currentColumnNodes;
			conditionMerger.nextIndex = conditions.length;
		}

		return conditionMerger;
	}

	private ArrayList<SdqlNode> getQualifyingNodes(Condition condition, Store store,
			ArrayList<SdqlNode> currentColumnNodes) {

		if (condition.getOperator() == Operator.EQUAL || condition.getOperator() == Operator.CONTAINS_EITHER) {
			if (LogicalUtil.isIndexNumeric(store, condition)) {
				HashMap<Long, ArrayList<SdqlNode>> columnNumericMap = store.getInvertedNumericIndex()
						.get(condition.getColumnIndex());
				for (String value : condition.getValue()) {
					Long numericValue = NumericUtil.getNumericValue(store, value, condition.getColumnIndex());
					if (columnNumericMap.get(numericValue) != null) {
						currentColumnNodes = mergeIndexNodes(currentColumnNodes, columnNumericMap.get(numericValue));
					}
				}
			} else {
				HashMap<String, ArrayList<SdqlNode>> columnMap = store.getInvertedIndex()
						.get(condition.getColumnIndex());

				if (LogicalUtil.isRangeType(store, condition) && condition.getOperator() == Operator.CONTAINS_EITHER) {
					HashMap<String, SearchableRange> rangeMap = store.getRanges().get(condition.getColumnIndex());
					for (String value : condition.getValue()) {
						Range rangeCondition = RangeParser.parseRange(value, store, condition.getColumnIndex());
						SearchableRange searchableRange = rangeMap.get(rangeCondition.getPrefix());
						if (searchableRange != null) {
							List<Range> qualifiedRanges = searchableRange.getInRange(rangeCondition.getLowerLimit());
							for (Range qualifiedRange : qualifiedRanges) {
								currentColumnNodes = mergeIndexNodes(currentColumnNodes,
										columnMap.get(qualifiedRange.toString()));
							}
						}
					}

				} else {
					for (String value : condition.getValue()) {
						if (LogicalUtil.isRangeType(store, condition)) {
							Range rangeCondition = RangeParser.parseRange(value, store, condition.getColumnIndex());
							value = rangeCondition.toString();
						}
						if (columnMap.get(value) != null) {
							currentColumnNodes = mergeIndexNodes(currentColumnNodes, columnMap.get(value));
						}
					}
				}

			}

		} else if (condition.getOperator() == Operator.CONTAINS_ALL) {

			ArrayList<SdqlNode> qualifyingNodes = null;

			if (LogicalUtil.isIndexNumeric(store, condition)) {
				HashMap<Long, ArrayList<SdqlNode>> columnNumericMap = store.getInvertedNumericIndex()
						.get(condition.getColumnIndex());
				for (String value : condition.getValue()) {
					Long numericValue = NumericUtil.getNumericValue(store, value, condition.getColumnIndex());
					if (columnNumericMap.get(numericValue) == null) {
						currentColumnNodes = new ArrayList<>();
						return currentColumnNodes;
					} else {
						if (qualifyingNodes == null) {
							qualifyingNodes = columnNumericMap.get(numericValue);
						} else {
							qualifyingNodes = getDuplicates(columnNumericMap.get(numericValue), qualifyingNodes);
						}

					}
				}

			} else {

				HashMap<String, ArrayList<SdqlNode>> columnMap = store.getInvertedIndex()
						.get(condition.getColumnIndex());

				if (LogicalUtil.isRangeType(store, condition)) {
					HashMap<String, SearchableRange> rangeMap = store.getRanges().get(condition.getColumnIndex());
					for (String value : condition.getValue()) {
						Range rangeCondition = RangeParser.parseRange(value, store, condition.getColumnIndex());
						SearchableRange searchableRange = rangeMap.get(rangeCondition.getPrefix());
						if (searchableRange == null) {
							currentColumnNodes = new ArrayList<>();
							return currentColumnNodes;
						} else {
							List<Range> qualifiedRanges = searchableRange.getInRange(rangeCondition.getLowerLimit());
							if (qualifiedRanges.size() == 0) {
								currentColumnNodes = new ArrayList<>();
								return currentColumnNodes;
							}

							ArrayList<SdqlNode> currentSetNodes = null;
							for (Range qualifiedRange : qualifiedRanges) {
								if (currentSetNodes == null) {
									currentSetNodes = columnMap.get(qualifiedRange.toString());
								} else {
									currentSetNodes = mergeIndexNodes(currentSetNodes,
											columnMap.get(qualifiedRange.toString()));

								}
							}

							if (qualifyingNodes == null) {
								qualifyingNodes = currentSetNodes;
							} else {
								qualifyingNodes = getDuplicates(currentSetNodes, qualifyingNodes);
							}

						}

					}

				} else {

					for (String value : condition.getValue()) {
						if (columnMap.get(value) == null) {
							currentColumnNodes = new ArrayList<>();
							return currentColumnNodes;
						} else {
							if (qualifyingNodes == null) {
								qualifyingNodes = columnMap.get(value);
							} else {
								qualifyingNodes = getDuplicates(columnMap.get(value), qualifyingNodes);
							}
						}
					}

				}

			}

			currentColumnNodes = qualifyingNodes != null ? qualifyingNodes : currentColumnNodes;

		} else if (condition.getOperator() == Operator.NOT_CONTAINS) {

			if (store.getSdqlColumns()[condition.getColumnIndex()].getSecondaryType() == null
					|| store.getSdqlColumns()[condition.getColumnIndex()].getSecondaryType()
							.getSecondaryCollectionType() == null) {

				String message = "Not_Contains operator is not supported for single data types. It is only supported for collections"
						+ condition;
				log.error(message);
				throw new NonSupportedOperationException(message);

			}

			Set<String> keysToSkip = new HashSet<>();

			if (LogicalUtil.isIndexNumeric(store, condition)) {
				HashMap<Long, ArrayList<SdqlNode>> columnNumericMap = store.getInvertedNumericIndex()
						.get(condition.getColumnIndex());
				for (String value : condition.getValue()) {
					Long numericValue = NumericUtil.getNumericValue(store, value, condition.getColumnIndex());
					if (columnNumericMap.get(numericValue) != null) {
						for (SdqlNode matchingNode : columnNumericMap.get(numericValue)) {
							keysToSkip.add(matchingNode.getStringValue());
						}
					}
				}
			} else {
				HashMap<String, ArrayList<SdqlNode>> columnMap = store.getInvertedIndex()
						.get(condition.getColumnIndex());

				if (LogicalUtil.isRangeType(store, condition)) {
					HashMap<String, SearchableRange> rangeMap = store.getRanges().get(condition.getColumnIndex());
					for (String value : condition.getValue()) {
						Range rangeCondition = RangeParser.parseRange(value, store, condition.getColumnIndex());
						SearchableRange searchableRange = rangeMap.get(rangeCondition.getPrefix());
						if (searchableRange != null) {
							List<Range> qualifiedRanges = searchableRange.getInRange(rangeCondition.getLowerLimit());
							for (Range qualifiedRange : qualifiedRanges) {
								for (SdqlNode matchingNode : columnMap.get(qualifiedRange.toString())) {
									keysToSkip.add(matchingNode.getStringValue());
								}
							}
						}
					}

				} else {
					for (String value : condition.getValue()) {
						if (columnMap.get(value) != null) {
							for (SdqlNode matchingNode : columnMap.get(value)) {
								keysToSkip.add(matchingNode.getStringValue());
							}
						}
					}
				}

			}

			HashMap<String, ArrayList<SdqlNode>> fullKeyMap = store.getCollectionFullKeyIndex()
					.get(condition.getColumnIndex());

			for (Entry<String, ArrayList<SdqlNode>> entry : fullKeyMap.entrySet()) {
				if (!keysToSkip.contains(entry.getKey())) {
					currentColumnNodes = mergeIndexNodes(currentColumnNodes, entry.getValue());
				}
			}

		} else if (condition.getOperator() == Operator.NOT_EQUAL) {
			if (LogicalUtil.isIndexNumeric(store, condition)) {

				Set<Long> conditionValues = new HashSet<>();
				for (String value : condition.getValue()) {
					conditionValues.add(NumericUtil.getNumericValue(store, value, condition.getColumnIndex()));
				}

				HashMap<Long, ArrayList<SdqlNode>> columnNumericMap = store.getInvertedNumericIndex()
						.get(condition.getColumnIndex());

				for (Entry<Long, ArrayList<SdqlNode>> entry : columnNumericMap.entrySet()) {
					if (!conditionValues.contains(entry.getKey())) {
						currentColumnNodes = mergeIndexNodes(currentColumnNodes, entry.getValue());
					}
				}

			} else {
				Set<String> conditionValues = new HashSet<>();
				conditionValues.addAll(condition.getValue());
				HashMap<String, ArrayList<SdqlNode>> columnMap = store.getInvertedIndex()
						.get(condition.getColumnIndex());

				for (Entry<String, ArrayList<SdqlNode>> entry : columnMap.entrySet()) {
					if (!conditionValues.contains(entry.getKey())) {
						currentColumnNodes = mergeIndexNodes(currentColumnNodes, entry.getValue());
					}
				}

			}

		} else if (condition.getOperator() == Operator.LESS_THAN
				|| condition.getOperator() == Operator.LESS_THAN_EQUAL) {
			boolean inclusiveFlag = condition.getOperator() == Operator.LESS_THAN_EQUAL ? true : false;
			String value = condition.getValue().get(0);

			if (LogicalUtil.isRangeType(store, condition)) {
				String message = "Less than operator not supported for ranges, Condition: " + condition;
				log.error(message);
				throw new NonSupportedOperationException(message);
			}

			if (LogicalUtil.isIndexNumeric(store, condition)) {
				HashMap<Long, ArrayList<SdqlNode>> columnNumericMap = store.getInvertedNumericIndex()
						.get(condition.getColumnIndex());
				OrderedKeys<Long> orderedNumerickeys = store.getInvertedNumericIndexKeys()
						.get(condition.getColumnIndex());

				List<Long> eligibleKeys = null;

				if (inclusiveFlag) {
					eligibleKeys = orderedNumerickeys
							.getAllLessThanEqual(NumericUtil.getNumericValue(store, value, condition.getColumnIndex()));
				} else {
					eligibleKeys = orderedNumerickeys
							.getAllLessThan(NumericUtil.getNumericValue(store, value, condition.getColumnIndex()));
				}

				for (Long key : eligibleKeys) {
					currentColumnNodes = mergeIndexNodes(currentColumnNodes, columnNumericMap.get(key));
				}
			} else {
				HashMap<String, ArrayList<SdqlNode>> columnMap = store.getInvertedIndex()
						.get(condition.getColumnIndex());
				OrderedKeys<NullableOrderedString> orderedStringkeys = store.getInvertedIndexKeys()
						.get(condition.getColumnIndex());

				List<NullableOrderedString> eligibleKeys = null;

				if (inclusiveFlag) {
					eligibleKeys = orderedStringkeys.getAllLessThanEqual(new NullableOrderedString(value));
				} else {
					eligibleKeys = orderedStringkeys.getAllLessThan(new NullableOrderedString(value));
				}

				for (NullableOrderedString key : eligibleKeys) {
					currentColumnNodes = mergeIndexNodes(currentColumnNodes, columnMap.get(key.toString()));
				}
			}

		} else if (condition.getOperator() == Operator.GREATER_THAN
				|| condition.getOperator() == Operator.GREATER_THAN_EQUAL) {
			boolean inclusiveFlag = condition.getOperator() == Operator.GREATER_THAN_EQUAL ? true : false;
			String value = condition.getValue().get(0);

			if (LogicalUtil.isRangeType(store, condition)) {
				String message = "Greater than operator not supported for ranges, Condition: " + condition;
				log.error(message);
				throw new NonSupportedOperationException(message);
			}

			if (LogicalUtil.isIndexNumeric(store, condition)) {
				HashMap<Long, ArrayList<SdqlNode>> columnNumericMap = store.getInvertedNumericIndex()
						.get(condition.getColumnIndex());
				OrderedKeys<Long> orderedNumerickeys = store.getInvertedNumericIndexKeys()
						.get(condition.getColumnIndex());

				List<Long> eligibleKeys = null;

				if (inclusiveFlag) {
					eligibleKeys = orderedNumerickeys.getAllGreaterThanEqual(
							NumericUtil.getNumericValue(store, value, condition.getColumnIndex()));
				} else {
					eligibleKeys = orderedNumerickeys
							.getAllGreaterThan(NumericUtil.getNumericValue(store, value, condition.getColumnIndex()));
				}
				for (Long key : eligibleKeys) {
					currentColumnNodes = mergeIndexNodes(currentColumnNodes, columnNumericMap.get(key));
				}
			} else {
				HashMap<String, ArrayList<SdqlNode>> columnMap = store.getInvertedIndex()
						.get(condition.getColumnIndex());
				OrderedKeys<NullableOrderedString> orderedStringkeys = store.getInvertedIndexKeys()
						.get(condition.getColumnIndex());

				List<NullableOrderedString> eligibleKeys = null;

				if (inclusiveFlag) {
					eligibleKeys = orderedStringkeys.getAllGreaterThanEqual(new NullableOrderedString(value));
				} else {
					eligibleKeys = orderedStringkeys.getAllGreaterThan(new NullableOrderedString(value));
				}
				for (NullableOrderedString key : eligibleKeys) {
					currentColumnNodes = mergeIndexNodes(currentColumnNodes, columnMap.get(key.toString()));
				}
			}

		} else if (condition.getOperator() == Operator.BETWEEN) {

			if (LogicalUtil.isRangeType(store, condition)) {
				String message = "Between operator not supported for ranges, Condition: " + condition;
				log.error(message);
				throw new NonSupportedOperationException(message);
			}

			String lowerValue = condition.getLowerValue();
			String upperValue = condition.getUpperValue();

			if (lowerValue == null || upperValue == null) {
				String message = "Insufficient data with Between operator, both lower and upper range miust be specified, condition: "
						+ condition;
				throw new InsufficientDataException(message);
			}

			if (LogicalUtil.isIndexNumeric(store, condition)) {
				HashMap<Long, ArrayList<SdqlNode>> columnNumericMap = store.getInvertedNumericIndex()
						.get(condition.getColumnIndex());
				OrderedKeys<Long> orderedNumerickeys = store.getInvertedNumericIndexKeys()
						.get(condition.getColumnIndex());

				List<Long> eligibleKeys = null;

				eligibleKeys = orderedNumerickeys.getAllBetween(
						NumericUtil.getNumericValue(store, lowerValue, condition.getColumnIndex()),
						NumericUtil.getNumericValue(store, upperValue, condition.getColumnIndex()));

				for (Long key : eligibleKeys) {
					currentColumnNodes = mergeIndexNodes(currentColumnNodes, columnNumericMap.get(key));
				}
			} else {
				HashMap<String, ArrayList<SdqlNode>> columnMap = store.getInvertedIndex()
						.get(condition.getColumnIndex());
				OrderedKeys<NullableOrderedString> orderedStringkeys = store.getInvertedIndexKeys()
						.get(condition.getColumnIndex());

				List<NullableOrderedString> eligibleKeys = null;

				eligibleKeys = orderedStringkeys.getAllBetween(new NullableOrderedString(lowerValue),
						new NullableOrderedString(upperValue));

				for (NullableOrderedString key : eligibleKeys) {
					currentColumnNodes = mergeIndexNodes(currentColumnNodes, columnMap.get(key.toString()));
				}
			}

		}

		return currentColumnNodes;

	}

	private TreeSet<Integer> getFilterColPos(Set<String> filterSet, Store store) {

		TreeSet<Integer> colPosSet = new TreeSet<>();

		if (filterSet.isEmpty()) {
			for (int i = 0; i < store.getSdqlColumns().length; i++) {
				colPosSet.add(i);
			}
		} else {
			for (String colName : filterSet) {
				if (store.getColumnIndex().get(colName.toUpperCase()) != null) {
					colPosSet.add(store.getColumnIndex().get(colName.toUpperCase()));
				}
			}
		}

		return colPosSet;
	}

	private Condition[] sortConditions(Store store, List<Condition> conditions) {

		Condition[] resultantConditions = new Condition[conditions.size()];
		Map<String, Integer> columnIndex = store.getColumnIndex();
		int i = 0;
		for (Condition condition : conditions) {
			Integer colIndex = columnIndex.get(condition.getColumnName().toUpperCase());
			if (colIndex != null) {
				resultantConditions[i++] = condition;
				condition.setColumnIndex(colIndex);

				if (condition.getValue() != null) {
					ListIterator<String> conditionValueIterator = condition.getValue().listIterator();

					while (conditionValueIterator.hasNext()) {
						String value = conditionValueIterator.next().trim();
						conditionValueIterator.set(value);
					}
				} else if (condition.getLowerValue() != null && condition.getUpperValue() != null) {
					condition.setLowerValue(condition.getLowerValue().trim());
					condition.setUpperValue(condition.getUpperValue().trim());
				}

			} else {
				String message = condition.getColumnName() + " is not a valid column";
				log.error(message);
				throw new ColumnNotFoundException(message);
			}
		}

		Arrays.sort(resultantConditions);

		return resultantConditions;
	}

	/**
	 * This function takes two sorted lists of Sdql Nodes and returns a merged and
	 * sorted list.
	 *
	 * @param currentValue nodes is the present, merged sorted list of nodes
	 *                     satisfying conditions so far.
	 * @param nextValue    nodes is the next qualifying nodes matching the next
	 *                     criteria. This will be merged with current value nodes
	 * @return This functions returns the sorted merged list of currentValue Nodes
	 *         and nextValue nodes. Sorting is done based on lower bound and upper
	 *         bound number of the node.
	 */

	private ArrayList<SdqlNode> mergeIndexNodes(ArrayList<SdqlNode> currentValue, ArrayList<SdqlNode> nextValue) {

		if (currentValue == null) {
			currentValue = new ArrayList<>();
		}

		if (nextValue == null) {
			nextValue = new ArrayList<>();
		}

		ArrayList<SdqlNode> mergedList = new ArrayList<SdqlNode>(currentValue.size() + nextValue.size());

		int currentValuePointer = 0;
		int nextValuePointer = 0;

		while (currentValuePointer < currentValue.size() || nextValuePointer < nextValue.size()) {

			if (currentValuePointer >= currentValue.size()) {
				mergedList.add(nextValue.get(nextValuePointer));
				nextValuePointer++;
				continue;
			} else if (nextValuePointer >= nextValue.size()) {
				mergedList.add(currentValue.get(currentValuePointer));
				currentValuePointer++;
				continue;
			}

			if (currentValue.get(currentValuePointer).getLowerBound() <= nextValue.get(nextValuePointer)
					.getLowerBound()) {
				mergedList.add(currentValue.get(currentValuePointer));
				if (currentValue.get(currentValuePointer).getLowerBound() == nextValue.get(nextValuePointer)
						.getLowerBound()) {
					nextValuePointer++;
				}
				currentValuePointer++;
			} else {
				mergedList.add(nextValue.get(nextValuePointer));
				nextValuePointer++;
			}

		}

		return mergedList;

	}

	private ArrayList<SdqlNode> binarySearchCurrentNodes(ArrayList<SdqlNode> prevColumnNodes,
			ArrayList<SdqlNode> currentColumnNodes) {

		ArrayList<SdqlNode> searchResultNodes = new ArrayList<>();

		for (SdqlNode sdqlNode : currentColumnNodes) {
			if (isContained(prevColumnNodes, sdqlNode)) {
				searchResultNodes.add(sdqlNode);
			}
		}
		return searchResultNodes;

	}

	private boolean isContained(ArrayList<SdqlNode> prevColumnNodes, SdqlNode sdqlNode) {

		int left = 0;
		int right = prevColumnNodes.size() - 1;

		boolean isContained = false;

		while (left <= right) {
			int mid = left + (right - left) / 2;
			if (sdqlNode.getLowerBound() < prevColumnNodes.get(mid).getLowerBound()) {
				right = mid - 1;
				continue;
			} else if (sdqlNode.getUpperBound() < prevColumnNodes.get(mid).getUpperBound()) {
				isContained = true;
				break;
			} else if (sdqlNode.getUpperBound() > prevColumnNodes.get(mid).getUpperBound()) {
				left = mid + 1;
				continue;
			}
		}

		return isContained;

	}

	private ArrayList<SdqlNode> inverseBinarySearchCurrentNodes(ArrayList<SdqlNode> prevColumnNodes,
			ArrayList<SdqlNode> currentColumnNodes) {

		ArrayList<SdqlNode> searchResultNodes = new ArrayList<>();

		for (SdqlNode sdqlNode : prevColumnNodes) {
			addAllContainedNodes(currentColumnNodes, sdqlNode, searchResultNodes);
		}
		return searchResultNodes;

	}

	private void addAllContainedNodes(ArrayList<SdqlNode> currentColumnNodes, SdqlNode prevNode,
			ArrayList<SdqlNode> searchResultNodes) {

		int left = 0;
		int right = currentColumnNodes.size() - 1;

		int lowerIndex = -1;
		int upperIndex = -1;

		while (left <= right) {
			int mid = left + (right - left) / 2;
			if (prevNode.getLowerBound() > currentColumnNodes.get(mid).getLowerBound()) {
				left = mid + 1;
				continue;
			} else if (prevNode.getLowerBound() < currentColumnNodes.get(mid).getLowerBound()
					&& prevNode.getUpperBound() < currentColumnNodes.get(mid).getLowerBound()) {
				right = mid - 1;
				continue;
			} else if (doesContain(prevNode, currentColumnNodes.get(mid))) {
				lowerIndex = mid;
				upperIndex = mid;

				int i = mid - 1;
				while (i >= 0) {
					if (doesContain(prevNode, currentColumnNodes.get(i))) {
						lowerIndex = i;
					} else {
						break;
					}

					i--;
				}

				i = mid + 1;

				while (i <= currentColumnNodes.size() - 1) {
					if (doesContain(prevNode, currentColumnNodes.get(i))) {
						upperIndex = i;
					} else {
						break;
					}

					i++;
				}
				break;
			}
		}

		if (lowerIndex != -1) {
			for (int i = lowerIndex; i <= upperIndex; i++) {
				searchResultNodes.add(currentColumnNodes.get(i));
			}
		}
	}

	private boolean doesContain(SdqlNode prevNode, SdqlNode currentNode) {
		return prevNode.getLowerBound() < currentNode.getLowerBound()
				&& prevNode.getUpperBound() > currentNode.getUpperBound();
	}

	private void buildPreFixMap(SdqlNode currentNode, int lastConditionColPos, TreeSet<Integer> filterColPos,
			Map<String, String> resultObject, Store store) {

		int firstColPos = filterColPos.first();
		while (lastConditionColPos >= firstColPos) {

			if (filterColPos.contains(lastConditionColPos)) {
				writeResultObject(currentNode, lastConditionColPos, resultObject, store);

			}

			lastConditionColPos--;
			currentNode = currentNode.getParent();
		}

	}

	private void writeResultObject(SdqlNode currentNode, int lastConditionColPos, Map<String, String> resultObject,
			Store store) {
		String columnName = store.getSdqlColumns()[lastConditionColPos].getColumnName();
		if (store.getSdqlColumns()[lastConditionColPos].isNumeric()) {
			String numericString = currentNode.getStringValue();
			if (currentNode.getStringValue().endsWith(".00")) {
				numericString = numericString.substring(0, numericString.length() - 3);
			}
			resultObject.put(columnName, numericString);
		} else {
			resultObject.put(columnName, currentNode.getStringValue());
		}
	}

	private void buildPostfixMap(SdqlNode currentNode, int nextColPos, TreeSet<Integer> filterColPos, int lasColPos,
			Map<String, String> resultObject, Store store) {

		if (nextColPos > lasColPos) {
			Map<String, String> newResultObject = new HashMap<>();
			newResultObject.putAll(resultObject);
			result.add(newResultObject);
			return;
		}

		for (Entry<String, SdqlNode> entry : currentNode.getChildren().entrySet()) {
			if (filterColPos.contains(nextColPos)) {
				writeResultObject(entry.getValue(), nextColPos, resultObject, store);
			}
			buildPostfixMap(entry.getValue(), nextColPos + 1, filterColPos, lasColPos, resultObject, store);
		}
	}

	/**
	 * This function just normalizes null text in all conditions, to either its
	 * appropriate string or long value
	 * 
	 * @param store      First sorted list of sdql nodes.
	 * @param conditions Second sorted list of sdql nodes.
	 */
	private void normalizeNull(Store store, Condition[] conditions) {

		for (Condition condition : conditions) {

			if (condition.getValue() != null) {
				boolean isNullFound = false;
				for (int i = 0; i < condition.getValue().size(); i++) {
					if (condition.getValue().get(i).equalsIgnoreCase(SdqlConstants.NULL)) {
						if (store.getSdqlColumns()[condition.getColumnIndex()].isNumeric()) {
							condition.getValue().set(i, SdqlConstants.LONG_NULL.toString());
							isNullFound = true;
						} else {
							condition.getValue().set(i, SdqlConstants.NULL);
							isNullFound = true;
						}
					}
				}
				if (condition.getOperator() == Operator.CONTAINS_EITHER_INCLUDING_NULL) {
					condition.setOperator(Operator.CONTAINS_EITHER);
					if (!isNullFound) {
						condition.getValue().add(SdqlConstants.NULL);
					}
				}
			} else {
				if (condition.getLowerValue().equalsIgnoreCase(SdqlConstants.NULL)) {
					if (store.getSdqlColumns()[condition.getColumnIndex()].isNumeric()) {
						condition.setLowerValue(SdqlConstants.LONG_NULL.toString());
					} else {
						condition.setLowerValue(SdqlConstants.NULL);
					}
				}

				if (condition.getUpperValue().equalsIgnoreCase(SdqlConstants.NULL)) {
					if (store.getSdqlColumns()[condition.getColumnIndex()].isNumeric()) {
						condition.setUpperValue(SdqlConstants.LONG_NULL.toString());
					} else {
						condition.setUpperValue(SdqlConstants.NULL);
					}
				}
			}

		}
	}

	/**
	 * Given two sorted list of sdql nodes, this function returns the duplicate
	 * nodes, present in the two lists
	 * 
	 * @param currentNodes       First sorted list of sdql nodes.
	 * @param newQualifyingNodes Second sorted list of sdql nodes.
	 * @return It returns the duplicate nodes in two sorted lists.
	 */
	private ArrayList<SdqlNode> getDuplicates(ArrayList<SdqlNode> currentNodes,
			ArrayList<SdqlNode> newQualifyingNodes) {

		if (currentNodes == null) {
			currentNodes = new ArrayList<>();
		}

		if (newQualifyingNodes == null) {
			newQualifyingNodes = new ArrayList<>();
		}

		ArrayList<SdqlNode> result = new ArrayList<>();

		int currentNodePointer = 0;
		int newQualifyingPointer = 0;

		while (currentNodePointer < currentNodes.size() && newQualifyingPointer < newQualifyingNodes.size()) {

			if (currentNodes.get(currentNodePointer).getLowerBound() < newQualifyingNodes.get(newQualifyingPointer)
					.getLowerBound()) {
				currentNodePointer++;

			} else if (currentNodes.get(currentNodePointer).getLowerBound() > newQualifyingNodes
					.get(newQualifyingPointer).getLowerBound()) {
				newQualifyingPointer++;
			} else {
				result.add(currentNodes.get(currentNodePointer));
				currentNodePointer++;
				newQualifyingPointer++;
			}

		}

		return result;
	}

}
