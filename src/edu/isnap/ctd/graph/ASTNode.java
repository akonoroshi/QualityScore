package edu.isnap.ctd.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import edu.isnap.ctd.graph.PrettyPrint.Params;

public class ASTNode implements INode {

	public String type, value, id;

	private ASTNode parent;

	private final List<ASTNode> children = new ArrayList<>();
	private final List<ASTNode> unmodifiableChildren = Collections.unmodifiableList(children);

	private final List<String> childRelations = new ArrayList<>();


	@Override
	public ASTNode parent() {
		return parent;
	}

	@Override
	public String type() {
		return type;
	}

	@Override
	public String value() {
		return value;
	}

	@Override
	public String id() {
		return id;
	}

	@Override
	public List<ASTNode> children() {
		return unmodifiableChildren;
	}

	public ASTNode(String type, String value, String id) {
		if (type == null) throw new IllegalArgumentException("'type' cannot be null");
		this.type = type;
		this.value = value;
		this.id = id;
	}

	public boolean addChild(ASTNode child) {
		return addChild(children.size(), child);
	}

	public boolean addChild(String relation, ASTNode child) {
		return addChild(children.size(), relation, child);
	}

	public boolean addChild(int index, ASTNode child) {
		int i = children.size();
		while (childRelations.contains(String.valueOf(i))) i++;
		return addChild(String.valueOf(i), child);
	}

	public boolean addChild(int index, String relation, ASTNode child) {
		if (childRelations.contains(relation)) return false;
		children.add(index, child);
		childRelations.add(index, relation);
		child.parent = this;
		return true;
	}

	public void removeChild(int index) {
		ASTNode child = children.remove(index);
		childRelations.remove(index);
		child.parent = null;
	}

	public void clearChildren() {
		children.forEach(c -> c.parent = null);
		children.clear();
		childRelations.clear();
	}

	public String prettyPrint(boolean showValues, Predicate<String> isBodyType) {
		Params params = new Params();
		params.showValues = showValues;
		params.isBodyType = isBodyType;
		return PrettyPrint.toString(this, params);
	}

	public static ASTNode parse(String jsonSource) throws JSONException {
		JSONObject object;
		try {
			object = new JSONObject(jsonSource);
		} catch (Exception e) {
			System.out.println("Error parsing JSON:");
			System.out.println(jsonSource);
			throw e;
		}
		return parse(object);
	}

	public static ASTNode parse(JSONObject object) {
		String type = object.getString("type");
		String value = object.has("value") ? object.getString("value") : null;
		String id = object.has("id") ? object.getString("id") : null;

		ASTNode node = new ASTNode(type, value, id);

		JSONObject children = object.optJSONObject("children");
		if (children != null) {
			JSONArray childrenOrder = object.optJSONArray("childrenOrder");

			if (childrenOrder == null) {
				// If we are not explicitly provided an order, just use the internal hash map's keys
				@SuppressWarnings("unchecked")
				Iterator<String> keys = children.keys();
				childrenOrder = new JSONArray();
				while (keys.hasNext()) {
					childrenOrder.put(keys.next());
				}
			}

			for (int i = 0; i < childrenOrder.length(); i++) {
				String relation = childrenOrder.getString(i);
				if (children.isNull(relation)) {
					node.addChild(relation, new ASTNode("null", null, null));
					continue;
				}

				ASTNode child = parse(children.getJSONObject(relation));
				node.addChild(relation, child);
			}
		}

		return node;
	}

	public JSONObject toJSON() {
		JSONObject object = new JSONObject();
		object.put("type", type);
		if (value != null) object.put("value", value);
		if (id != null) object.put("id", id);
		if (children.size() > 0) {
			JSONObject children = new JSONObject();
			JSONArray childrenOrder = new JSONArray();
			for (int i = 0; i < this.children.size(); i++) {
				String relation = childRelations.get(i);
				children.put(relation, this.children.get(i).toJSON());
				childrenOrder.put(relation);
			}
			object.put("children", children);
			object.put("childrenOrder", childrenOrder);
		}
		return object;
	}

	public void autoID(String prefix) {
		autoID(prefix, new AtomicInteger(0));
	}

	private void autoID(String prefix, AtomicInteger id) {
		if (this.id == null) this.id = prefix + id.getAndIncrement();
		for (ASTNode child : children) {
			child.autoID(prefix, id);
		}
	}

	public ASTNode copy() {
		ASTNode copy = shallowCopy();
		for (int i = 0; i < children.size(); i++) {
			ASTNode child = children.get(i);
			copy.addChild(childRelations.get(i), child == null ? null : child.copy());
		}
		return copy;
	}

	public ASTNode shallowCopy() {
		return new ASTNode(type, value, id);
	}

	public void recurse(Consumer<ASTNode> action) {
		action.accept(this);
		this.children.stream()
		.filter(child -> child != null)
		.forEach(child -> child.recurse(action));
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null) return false;
		if (obj == this) return true;
		if (obj.getClass() != getClass()) return false;
		ASTNode rhs = (ASTNode) obj;
		EqualsBuilder builder = new EqualsBuilder();
		builder.append(type, rhs.type);
		builder.append(value, rhs.value);
		builder.append(id, rhs.id);
		builder.append(children, rhs.children);
		builder.append(childRelations, rhs.childRelations);
		return builder.isEquals();
	}

	@Override
	public int hashCode() {
		HashCodeBuilder builder = new HashCodeBuilder(9, 15);
		builder.append(type);
		builder.append(value);
		builder.append(id);
		builder.append(children);
		builder.append(childRelations);
		return builder.toHashCode();
	}
}
