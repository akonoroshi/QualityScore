package edu.isnap.rating.data;

import java.util.ArrayList;

import edu.isnap.node.ASTSnapshot;

@SuppressWarnings("serial")
public class Trace extends ArrayList<ASTSnapshot> implements Comparable<Trace> {
	public final String id;
	public final String assignmentID;

	public ASTSnapshot getFinalSnapshot() {
		if (isEmpty()) return null;
		return get(size() - 1);
	}

	public Trace(String id, String assignmentID) {
		this.id = id;
		this.assignmentID = assignmentID;
	}

	@Override
	public int compareTo(Trace o) {
		return id.compareTo(o.id);
	}
}