package edu.isnap.rating;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import edu.isnap.ctd.graph.ASTNode;
import edu.isnap.ctd.util.Diff;
import edu.isnap.ctd.util.Diff.ColorStyle;
import edu.isnap.hint.util.Spreadsheet;
import edu.isnap.rating.EditExtractor.Deletion;
import edu.isnap.rating.EditExtractor.Edit;
import edu.isnap.rating.TutorHint.Priority;
import edu.isnap.rating.TutorHint.Validity;

public class RateHints {

	public final static String GS_SPREADSHEET = "gold-standard.csv";
	public final static String ALGORITHMS_DIR = "algorithms";
	public final static String TRAINING_FILE = "training.csv.gz";
	public static final String REQUEST_FILE = "requests.csv.gz";

	public final static String DATA_ROOT_DIR = "../data/hint-rating/";
	public final static String ISNAP_F16_S17_DATA_DIR = DATA_ROOT_DIR + "isnapF16-S17/";
	public final static String ISNAP_F16_F17_DATA_DIR = DATA_ROOT_DIR + "isnapF16-F17/";
	public final static String ITAP_S16_DATA_DIR = DATA_ROOT_DIR + "itapS16/";

	public static void rateDir(String path, RatingConfig config, boolean write)
			throws FileNotFoundException, IOException {
		GoldStandard standard = GoldStandard.parseSpreadsheet(path + GS_SPREADSHEET);
		File algorithmsFolder = new File(path, ALGORITHMS_DIR);
		if (!algorithmsFolder.exists() || !algorithmsFolder.isDirectory()) {
			throw new RuntimeException("Missing algorithms folder");
		}
		for (File algorithmFolder : algorithmsFolder.listFiles(file -> file.isDirectory())) {
			HintSet hintSet = HintSet.fromFolder(algorithmFolder.getName(), config,
					algorithmFolder.getPath());
			System.out.println(hintSet.name);
			HintRatingSet ratings = rate(standard, hintSet);
			ratings.writeAllHints(path + "/" + RateHints.ALGORITHMS_DIR + "/" +
					algorithmFolder.getName() + ".csv");
		}
	}

	public static HintRatingSet rate(GoldStandard standard, HintSet hintSet) {
		return rate(standard, hintSet, false);
	}

	public static HintRatingSet rate(GoldStandard standard, HintSet hintSet, boolean debug) {
		RatingConfig config = hintSet.config;
		HintRatingSet ratingSet = new HintRatingSet(hintSet.name);
		EditExtractor extractor = new EditExtractor(config);
		for (String assignmentID : standard.getAssignmentIDs()) {
			System.out.println("----- " + assignmentID + " -----");

			for (String requestID : standard.getRequestIDs(assignmentID)) {

				List<TutorHint> validHints = standard.getValidHints(assignmentID, requestID);

				// Make sure there is at least one hint with the required validity; otherwise,
				// assume there are no valid tutor hints and we should continue
				Validity requiredValidity = config.highestRequiredValidity();
				if (!validHints.stream()
						.anyMatch(hint -> hint.validity.isAtLeast(requiredValidity))) {
					continue;
				}

				RequestRating requestRating = new RequestRating(requestID, assignmentID,
						validHints.get(0).from, config);

				// Create an initial list of hints that are not matched to a tutor hint
				List<HintOutcome> unmatchedHints = new ArrayList<>(hintSet.getOutcomes(requestID));
				if (unmatchedHints.isEmpty()) {
					System.err.printf("No hints generated for request %s/%s.\n",
							assignmentID, requestID);
				}

				// First find full matches and remove any hints that match
				for (int i = 0; i < unmatchedHints.size(); i++) {
					HintRating rating = findMatchingEdit(validHints, unmatchedHints.get(i), config);
					if (rating != null) {
						requestRating.add(rating);
						unmatchedHints.remove(i--);
					}
				}
				// Then find any partial matches in the remaining hints
				for (HintOutcome hint : unmatchedHints) {
					HintRating rating = findPartiallyMatchingEdit(
							validHints, hint, config, extractor);
					requestRating.add(rating);
				}
				requestRating.sort();
				if (debug) {
					requestRating.printRatings(validHints.get(0).from, config, validHints);
				}
				ratingSet.add(requestRating);
				requestRating.printSummary();
			}

			ratingSet.printSummary(assignmentID);
		}
		return ratingSet;
	}

	private static ASTNode pruneImmediateChildren(ASTNode node, Predicate<String> condition) {
		for (int i = 0; i < node.children().size(); i++) {
			ASTNode child = node.children().get(i);
			if (condition.test(child.type) && child.children().isEmpty()) {
				node.removeChild(i--);
			}
		}
		return node;
	}

	/**
	 * Prunes nodes from the given "to" AST based on the settings in the config.
	 * Note: For efficiency this modifies with given node - it does not return a copy.
	 */
	public static void pruneNewNodesTo(ASTNode from, ASTNode to, RatingConfig config) {
		// Create a list of nodes before iteration, since we'll be modifying children
		List<ASTNode> toNodes = new ArrayList<>();
		to.recurse(node -> toNodes.add(node));
		// Reverse the order so children are pruned before parents
		Collections.reverse(toNodes);

		// Remove nodes that should be pruned if childless
		for (ASTNode node : toNodes) {
			if (node.parent() == null) continue;
			// All empty-type nodes can be pruned, since they're just placeholders
			if (node.hasType(ASTNode.EMPTY_TYPE) ||
					// Also prune some nodes that have no meaning without children (e.g. scripts)
					(node.children().size() == 0 && config.trimIfChildless(node.type()))) {
				node.parent().removeChild(node.index());
			}
		}

		// Identify new nodes and prune their children.
		List<ASTNode> addedNodes = EditExtractor.getInsertedAndRenamedNodes(from, to);
		// Reverse sort by depth to prune children first
		addedNodes.sort(Comparator.comparing(node -> -node.depth()));
		for (ASTNode node : addedNodes) {
			if (node.parent() == null) continue;
			// If this node was created/changed in the hint, prune its immediate children for
			// nodes that are added automatically, according to the config, e.g. literal nodes
			// in Snap.
			pruneImmediateChildren(node, config::trimIfParentIsAdded);
		}
	}

	public static ASTNode normalizeNewValuesTo(ASTNode from, ASTNode to, RatingConfig config) {
		to = to.copy();

		// Get a set of all the node values used in the original AST. We don't differentiate values
		// by type, since multiple types can share values (e.g. varDecs and vars)
		Set<String> usedValues = new HashSet<>();
		from.recurse(node -> usedValues.add(node.value));

		// Create a list of nodes before iteration, since we'll be modifying children
		List<ASTNode> toNodes = new ArrayList<>();
		to.recurse(node -> toNodes.add(node));

		for (ASTNode node : toNodes) {
			if (node.parent() == null) continue;

			if (node.value != null && !usedValues.contains(node.value)) {
				// If this node's value is new, we may normalize it
				boolean normalize = true;
				// First check if it's a new numeric literal and the config wants to normalize it
				if (config.useSpecificNumericLiterals()) {
					try {
						Double.parseDouble(node.value);
						normalize = false;
					} catch (NumberFormatException e) { }
				}
				if (normalize) {
					// If so, we replace its value with null, so all new values appear the same
					ASTNode parent = node.parent();
					ASTNode replacement = new ASTNode(node.type, null, node.id);
					int index = node.index();
					parent.removeChild(index);
					parent.addChild(index, replacement);
					for (ASTNode child : node.children()) {
						replacement.addChild(child);
					}
					node.clearChildren();
				}
			}
		}

		return to;
	}

	public static HintRating findMatchingEdit(List<TutorHint> validHints, HintOutcome outcome,
			RatingConfig config) {
		if (validHints.isEmpty()) return new HintRating(outcome);

		ASTNode fromNode = validHints.get(0).from;
		ASTNode outcomeNode = normalizeNewValuesTo(fromNode, outcome.result, config);
		pruneNewNodesTo(fromNode, outcomeNode, config);
		for (TutorHint tutorHint : validHints) {
			ASTNode tutorOutcomeNode = normalizeNewValuesTo(fromNode, tutorHint.to, config);
			pruneNewNodesTo(fromNode, tutorOutcomeNode, config);
			if (outcomeNode.equals(tutorOutcomeNode)) {
				return new HintRating(outcome, tutorHint, MatchType.Full);
			}

			if (outcome.result.equals(tutorHint.to)) {
				System.out.println("Matching hint:");
				System.out.println(ASTNode.diff(tutorHint.from, outcome.result, config));
				System.out.println("Difference in normalized nodes:");
				System.out.println(ASTNode.diff(tutorOutcomeNode, outcomeNode, config, 2));
				System.out.println("Tutor normalizing:");
				System.out.println(ASTNode.diff(tutorHint.to, tutorOutcomeNode, config, 2));
				System.out.println("Outcome normalizing:");
				System.out.println(ASTNode.diff(outcome.result, outcomeNode, config, 2));
				throw new RuntimeException("Normalized nodes should be equal if nodes are equal!");
			}
		}
		return null;
	}

	@Deprecated
	protected static List<HintRating> findMatchingEditGroups(List<TutorHint> validHints,
			List<HintOutcome> unmatchedHints, RatingConfig config, EditExtractor extractor) {
		List<HintRating> ratings = new ArrayList<>();

		if (validHints.isEmpty()) return ratings;
		ASTNode fromNode = validHints.get(0).from;

		Map<TutorHint, Set<Edit>> tutorEditMap = new IdentityHashMap<>();
		for (TutorHint hint : validHints) {
			Set<Edit> edits = extractor.getEdits(fromNode,
					normalizeNewValuesTo(fromNode, hint.to, config));
			tutorEditMap.put(hint, edits);
		}

		Map<HintOutcome, Set<Edit>> outcomeEditMap = new IdentityHashMap<>();
		for (HintOutcome hint : unmatchedHints) {
			Set<Edit> edits = extractor.getEdits(fromNode,
					normalizeNewValuesTo(fromNode, hint.result, config));
			outcomeEditMap.put(hint, edits);
		}

		// Sort but then reverse, so highest priority hints come first
		Collections.sort(validHints);
		Collections.reverse(validHints);

		for (TutorHint tutorHint : validHints) {
			Set<Edit> tutorEdits = tutorEditMap.get(tutorHint);
			// First filter out only hints that are subsets of the given tutor hint
			Map<HintOutcome, Set<Edit>> possible = new IdentityHashMap<>();
			for (HintOutcome outcome : outcomeEditMap.keySet()) {
				Set<Edit> outcomeEdits = outcomeEditMap.get(outcome);
				if (tutorEdits.containsAll(outcomeEdits)) {
					possible.put(outcome, outcomeEdits);
				}
			}
			if (possible.size() == 0) continue;

			List<HintOutcome> possibleOutcomes = new ArrayList<>(possible.keySet());
			possibleOutcomes.sort(Comparator.comparing(o -> -possible.get(o).size()));

			Set<Edit> matchingEdits = new HashSet<>();
			List<HintOutcome> matchingOutcomes = new ArrayList<>();
			for (HintOutcome outcome : possibleOutcomes) {
				Set<Edit> outcomeEdits = possible.get(outcome);
				if (matchingEdits.containsAll(outcomeEdits)) continue;
				matchingOutcomes.add(outcome);
				matchingEdits.addAll(outcomeEdits);
			}

			if (matchingEdits.stream().allMatch(e -> e instanceof Deletion) &&
					!tutorEdits.stream().allMatch(e -> e instanceof Deletion)) {
				continue;
			}

//			List<HintOutcome> matchingOutcomes =
//					findPartialMatches(possible, tutorEdits, new HashSet<>(), config);
//			if (matchingOutcomes == null) continue;

			matchingOutcomes.forEach(outcome -> outcomeEditMap.remove(outcome));

			if (!tutorHint.validity.isAtLeast(Validity.MultipleTutors)) continue;
			if (matchingOutcomes.size() == 1) continue;
			System.out.println("Partial match: ");
			System.out.println("Tutor hint: ");
			System.out.println(ASTNode.diff(fromNode, tutorHint.to, config));
			System.out.println("Alg hints: ");
			for (HintOutcome outcome : matchingOutcomes) {
				System.out.println(ASTNode.diff(fromNode, outcome.result, config));
			}
			System.out.println("Edits:");
			EditExtractor.printEditsComparison(
					tutorEdits, matchingEdits, "Tutor Hint", "Alg Hints");

		}

		return ratings;
	}

	@Deprecated
	protected static List<HintOutcome> findPartialMatches(Map<HintOutcome, Set<Edit>> outcomeEditMap,
			Set<Edit> toMatch, Set<Edit> matched, RatingConfig config) {
		for (HintOutcome outcome : outcomeEditMap.keySet()) {
			Set<Edit> missing = new HashSet<>(toMatch);
			Set<Edit> outcomeEdits = outcomeEditMap.get(outcome);
			missing.removeAll(outcomeEdits);
			if (missing.size() == toMatch.size()) continue;
			matched = new HashSet<>(matched);
			matched.addAll(outcomeEdits);
			if (missing.stream().allMatch(e -> config.trimIfParentIsAdded(e.node.type))) {
				List<HintOutcome> list = new ArrayList<>();
				list.add(outcome);
				return list;
			}
			List<HintOutcome> list = findPartialMatches(outcomeEditMap, missing, matched, config);
			if (list != null) {
				list.add(outcome);
				return list;
			}
		}
		return null;
	}

	public static HintRating findPartiallyMatchingEdit(List<TutorHint> validHints,
			HintOutcome outcome, RatingConfig config, EditExtractor extractor) {
		if (validHints.isEmpty()) return new HintRating(outcome);
		ASTNode fromNode = validHints.get(0).from;

		// Run again to get a version that's unpruned
		ASTNode outcomeNode = normalizeNewValuesTo(fromNode, outcome.result, config);
		Set<Edit> outcomeEdits = extractor.getEdits(fromNode, outcomeNode);
		if (outcomeEdits.size() == 0) return new HintRating(outcome);

		// There are three types of partial matches, only the last of which is detected here:
		// 1) The algorithm matches the hint perfectly in essence, but it misses some required
		//    elements of the AST. We treat these as exact matches, and attempt to detect them
		//    by pruning away less meaningful parts of the AST in findMatchingEdit().
		// 2) The algorithm creates 2 hints that together cover most of a tutor hint. We choose not
		//    to treat this as a special case, since all hints should be evaluated independently.
		//    However, with some hint interfaces (e.g. iSnap) they may not in fact be presented
		//    independently, so this is not a perfect solution.
		// 3) The algorithm matches part of the tutor hint (e.g. inserting something), but is
		//    missing other edits needed to make the hints complete, clear and not confusing.
		//    As long as the algorithm conveys a meaningful part of the tutor's hint, we call these
		//    partial matches. We define meaningful here as "not only deletions," but of course this
		//    is an imperfect definition.

		// Sort but then reverse, so highest priority hints come first
		Collections.sort(validHints);
		Collections.reverse(validHints);

		Set<Edit> bestOverlap = new HashSet<>();
		TutorHint bestHint = null;
		for (TutorHint tutorHint : validHints) {
			ASTNode tutorOutcomeNode = normalizeNewValuesTo(fromNode, tutorHint.to, config);
			Set<Edit> tutorEdits = extractor.getEdits(fromNode, tutorOutcomeNode);
			if (tutorEdits.size() == 0) continue;
			Set<Edit> overlap = new HashSet<>(tutorEdits);
			overlap.retainAll(outcomeEdits);
//			if (overlap.size() > bestOverlap.size()) {
			if (overlap.size() == outcomeEdits.size()) {
				if (overlap.size() == tutorEdits.size()) {
					System.out.println("Tutor hint: ");
					System.out.println(ASTNode.diff(fromNode, tutorOutcomeNode, config));
					System.out.println("Alg hint: ");
					System.out.println(ASTNode.diff(fromNode, outcomeNode, config));
					EditExtractor.printEditsComparison(
							tutorEdits, outcomeEdits, "Tutor Hint", "Alg Hint");
					throw new RuntimeException("Edits should not match if hint outcomes did not!");
				}
				bestOverlap = overlap;
				bestHint = tutorHint;
				break;
			}
		}
		if (!bestOverlap.isEmpty()) {
			// If the overlap is only deletions, we do not count this as a partial match
			if (!bestOverlap.stream().allMatch(e -> e instanceof Deletion)) {
				return new HintRating(outcome, bestHint, MatchType.Partial);
			} else {
//				printPartialMatch(config, extractor, fromNode, outcomeNode, outcomeEdits, bestHint);
			}
		}
		return new HintRating(outcome);
	}

	protected static void printPartialMatch(RatingConfig config, EditExtractor extractor,
			ASTNode fromNode, ASTNode outcomeNode, Set<Edit> outcomeEdits, TutorHint bestHint) {
		if (bestHint.validity.isAtLeast(Validity.MultipleTutors)) {
			Set<Edit> tutorEdits = new HashSet<>();
			if (bestHint != null) {
				System.out.println("Tutor Hint:");
				ASTNode tutorOutcomeNode = normalizeNewValuesTo(fromNode, bestHint.to, config);
				System.out.println(Diff.diff(
						fromNode.prettyPrint(true, config),
						tutorOutcomeNode.prettyPrint(true, config)));
				tutorEdits = extractor.getEdits(bestHint.from, tutorOutcomeNode);
			}
			System.out.println("Alg Hint:");
			System.out.println(Diff.diff(
					fromNode.prettyPrint(true, config),
					outcomeNode.prettyPrint(true, config), 2));
			EditExtractor.printEditsComparison(
					tutorEdits, outcomeEdits, "Tutor Hint", "Alg Hint");
			System.out.println("-------------------");
		}
	}

	public static enum MatchType {
		None, Partial, Full;

		public boolean isAtLeast(MatchType type) {
			return this.ordinal() >= type.ordinal();
		}
	}

	@SuppressWarnings("serial")
	public static class HintRatingSet extends ArrayList<RequestRating> {
		public final String name;

		public HintRatingSet(String name) {
			this.name = name;
		}

		public void printSummary(String assignmentID) {
			List<RequestRating> ratings = stream()
					.filter(rating -> rating.assignmentID.equals(assignmentID))
					.collect(Collectors.toList());
			if (ratings.size() == 0) return;
			double[] validityArrayMean = null;
			for (RequestRating rating : ratings) {
				double[] ratingArray = rating.getValidityArray();
				if (validityArrayMean == null) {
					validityArrayMean = ratingArray;
				} else {
					for (int i = 0; i < validityArrayMean.length; i++) {
						validityArrayMean[i] += ratingArray[i];
					}
				}
			}
			int nSnapshots = ratings.size();
			for (int i = 0; i < validityArrayMean.length; i++) validityArrayMean[i] /= nSnapshots;
			double priorityMeanFull = stream()
					.mapToDouble(rating -> rating.getPriorityScore(false))
					.average().getAsDouble();
			double priorityMeanPartial = stream()
					.mapToDouble(rating -> rating.getPriorityScore(true))
					.average().getAsDouble();
			System.out.printf("TOTAL: %s / %.03f (%.03f)p\n",
					RequestRating.validityArrayToString(validityArrayMean, 3),
					priorityMeanFull, priorityMeanPartial);
		}

		public void writeAllHints(String path) throws FileNotFoundException, IOException {
			Spreadsheet spreadsheet = new Spreadsheet();
			writeAllHints(spreadsheet);
			spreadsheet.write(path);
		}

		public void writeAllHints(Spreadsheet spreadsheet) {
			forEach(rating -> rating.writeAllHints(spreadsheet));
		}

		public void writeAllRatings(Spreadsheet spreadsheet) {
			forEach(rating -> rating.writeRating(spreadsheet));
		}
	}

	@SuppressWarnings("serial")
	public static class RequestRating extends ArrayList<HintRating> {
		public final String requestID;
		public final String assignmentID;
		public final ASTNode requestNode;
		private final RatingConfig config;

		public RequestRating(String requestID, String assignmentID, ASTNode requestNode,
				RatingConfig config) {
			this.requestID = requestID;
			this.assignmentID = assignmentID;
			this.requestNode = requestNode;
			this.config = config;
		}

		public void sort() {
			sort((r1, r2) -> r1.hint == null ? 0 : r1.hint.compareTo(r2.hint));
		}

		public void writeAllHints(Spreadsheet spreadsheet) {
			for (int i = 0; i < size(); i++) {
				get(i).addToSpreadsheet(spreadsheet, i, getTotalWeight(), requestNode, config);
			}
			if (isEmpty()) {
				HintOutcome noOutcome = new HintOutcome(null, assignmentID, requestID, 1);
				new HintRating(noOutcome).addToSpreadsheet(spreadsheet, 0, 1, requestNode, config);
			}
		}

		public void writeRating(Spreadsheet spreadsheet) {
			spreadsheet.newRow();
			spreadsheet.put("assignmentID", assignmentID);
			spreadsheet.put("requestID", requestID);
			double weight = getTotalWeight();
			for (Validity validity : Validity.values()) {
				if (validity == Validity.NoTutors) continue;
				for (MatchType type : MatchType.values()) {
					if (type == MatchType.None) continue;
					double validityValue = weight == 0 ? 0 :
						(validityWeight(type, validity, true) / weight);
					spreadsheet.put(validity + "_" + type, validityValue);
					spreadsheet.put(validity + "_" + type + "_validWeight",
							validityWeight(type, validity, true));
					spreadsheet.put(validity + "_" + type  + "_validCount",
							validityWeight(type, validity, false));
				}
			}
			spreadsheet.put("totalWeight", weight);
			spreadsheet.put("totalCount", size());
		}

		public double validityWeight(MatchType minMatchType, Validity minValidity,
				boolean useWeights) {
			return stream()
					.mapToDouble(rating -> rating.matchType.isAtLeast(minMatchType)
							&& rating.validity().isAtLeast(minValidity) && !rating.isTooSoon()
							? (useWeights ? rating.hint.weight() : 1) : 0)
					.sum();
		}

		private double getTotalWeight() {
			return stream().mapToDouble(r -> r.hint.weight()).sum();
		}

		protected double[] getValidityArray() {
			int nValues = Validity.values().length - 1;
			double[] validityArray = new double[nValues * 2];
			if (isEmpty()) return validityArray;
			for (int i = 0; i < nValues; i++) {
				validityArray[i * 2] =
						validityWeight(MatchType.Full, Validity.fromInt(i + 1), true);
				validityArray[i * 2 + 1] =
						validityWeight(MatchType.Partial, Validity.fromInt(i + 1), true);
			}
			double totalWeight = getTotalWeight();;
			for (int i = 0; i < validityArray.length; i++) {
				validityArray[i] /= totalWeight;
			}
			return validityArray;
		}

		protected double getPriorityScore(boolean countPartial) {
			if (isEmpty()) return 0;
			return stream()
					.filter(r -> r.priority() != null &&
							(countPartial || r.matchType == MatchType.Full))
					.mapToDouble(r -> r.hint.weight() * r.priority().points())
					.sum() / getTotalWeight();
		}

		public void printSummary() {
			double[] validityArray = getValidityArray();
			System.out.printf("%s: %s / %.02f (%.02f)p\n",
					requestID, validityArrayToString(validityArray, 2),
					getPriorityScore(false), getPriorityScore(true));
		}

		private static String validityArrayToString(double[] array, int digits) {
			String format = "%.0" + digits + "f (%.0" + digits + "f)";
			StringBuilder sb = new StringBuilder();
			sb.append("[");
			for (int i = 0; i < array.length / 2; i++) {
				if (i > 0) sb.append(", ");
				sb.append(String.format(format, array[i * 2], array[i * 2 + 1]));
			}
			sb.append("]v");
			return sb.toString();
		}

		private void printRatings(ASTNode from, RatingConfig config, List<TutorHint> validHints) {
			if (isEmpty()) return;
			HintOutcome firstOutcome = get(0).hint;
			System.out.println("+====+ " + firstOutcome.assignmentID + " / " +
					firstOutcome.requestID + " +====+");
			Set<TutorHint> valid = validHints.stream()
					.filter(hint -> hint.validity.isAtLeast(Validity.MultipleTutors) &&
							(hint.priority == null || hint.priority != Priority.TooSoon))
					.collect(Collectors.toSet());
			System.out.println(from.prettyPrint(true, config));
			for (int tooSoonRound = 0; tooSoonRound < 2; tooSoonRound++) {
				for (MatchType type : MatchType.values()) {
					boolean tooSoon = tooSoonRound == 1;
					List<HintRating> matching = stream()
							.filter(rating -> tooSoon ? rating.isTooSoon() :
								(rating.matchType == type && !rating.isTooSoon()))
							.collect(Collectors.toList());
					if (!matching.isEmpty()) {
						String label = tooSoon ? "Too Soon" : type.toString();
						System.out.println("               === " + label + " ===");
						for (HintRating rating : matching) {
							System.out.println("Hint ID: " + rating.hint.id);
							System.out.println("Weight: " + rating.hint.weight());
							System.out.println(rating.hint.resultString(from, config));
							System.out.println("-------");
							valid.remove(rating.match);
						}
					}
					if (tooSoon) break;
				}
			}
//			if (!valid.isEmpty()) System.out.println("               === Missed ===");
//			for (TutorHint missed : valid) {
//				System.out.println(missed.hintID);
//				System.out.println(missed.toDiff(config));
//				System.out.println("-------");
//			}
		}
	}

	public static class HintRating {
		public final HintOutcome hint;
		public final TutorHint match;
		public final MatchType matchType;

		public Validity validity() {
			return match == null ? Validity.NoTutors : match.validity;
		}

		public boolean isTooSoon() {
			return match != null && match.priority == Priority.TooSoon;
		}

		public Priority priority() {
			return match == null ? null : match.priority;
		}

		public HintRating(HintOutcome hint) {
			this(hint, null, MatchType.None);
		}

		public HintRating(HintOutcome hint, TutorHint match, MatchType matchType) {
			this.hint = hint;
			this.match = match;
			this.matchType = matchType;
		}

		public void addToSpreadsheet(Spreadsheet spreadsheet, int order, double totalWeight,
				ASTNode requestNode, RatingConfig config) {
			spreadsheet.newRow();
			spreadsheet.put("assignmentID", hint.assignmentID);
			spreadsheet.put("requestID", hint.requestID);
			spreadsheet.put("hintID", hint.id);
			spreadsheet.put("order", order);
			spreadsheet.put("weight", hint.weight());
			spreadsheet.put("weightNorm", hint.weight() / totalWeight);
			Integer matchID = null, validity = null, priority = null;
			if (match != null) {
				matchID = match.hintID;
				validity = match.validity.value;
				priority = match.priority == null ? null : match.priority.value;
			}
			spreadsheet.put("matchID", matchID);
			spreadsheet.put("validity", validity);
			spreadsheet.put("priority", priority);
			spreadsheet.put("type", matchType.toString());
			spreadsheet.put("outcome", hint.result == null ? "" : hint.result.toJSON().toString());
			ColorStyle oldStyle = Diff.colorStyle;
			Diff.colorStyle = ColorStyle.HTML;
			spreadsheet.put("diff", hint.result == null ?
					"" : ASTNode.diff(requestNode, hint.result, config));
			Diff.colorStyle = oldStyle;
			Map<String, String> properties = hint.getDebuggingProperties();
			for (String key : properties.keySet()) {
				spreadsheet.put("p_" + key, properties.get(key));
			}
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			sb.append(hint.assignmentID).append(" / ").append(hint.requestID)
			.append(": ").append(hint.weight())
			.append(" - ").append(matchType);
			return sb.toString();
		}
	}
}
