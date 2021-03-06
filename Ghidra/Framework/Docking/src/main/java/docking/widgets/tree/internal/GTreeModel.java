/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package docking.widgets.tree.internal;

import java.util.ArrayList;
import java.util.List;

import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.TreeModel;
import javax.swing.tree.TreePath;

import docking.widgets.tree.GTree;
import docking.widgets.tree.GTreeNode;
import ghidra.util.Swing;
import ghidra.util.SystemUtilities;

public class GTreeModel implements TreeModel {

	private volatile GTreeNode root;
	private List<TreeModelListener> listeners = new ArrayList<TreeModelListener>();
	private boolean isFiringNodeStructureChanged;
	private volatile boolean eventsEnabled = true;

	/**
	 * Constructs a GTreeModel with the given root node.
	 * 
	 * @param root The root of the tree.
	 * @param isThreaded True signals to perform all tree tasks in a threaded environment to 
	 *        avoid hanging the swing thread.
	 */
	public GTreeModel(GTreeNode root) {
		this.root = root;
	}

	public void setRootNode(GTreeNode root) {
		this.root = root;
		fireRootChanged();
	}

	@Override
	public Object getRoot() {
		return root;
	}

	public GTreeNode getModelRoot() {
		return root;
	}

	@Override
	public void addTreeModelListener(TreeModelListener l) {
		listeners.add(l);
	}

	@Override
	public void removeTreeModelListener(TreeModelListener l) {
		listeners.remove(l);
	}

	@Override
	public Object getChild(Object parent, int index) {
		try {
			GTreeNode gTreeParent = (GTreeNode) parent;
			return gTreeParent.getChild(index);
		}
		catch (IndexOutOfBoundsException e) {
			// This can happen if the client code mutates the children of this node in a background
			// thread such that there are fewer child nodes on this node, and then before the tree
			// is notified, the JTree attempts to access a child that is no longer present. The
			// GTree design specifically allows this situation to occur as a trade off for 
			// better performance when performing bulk operations (such as filtering).  If this
			// does occur, this can be handled easily by temporarily returning a dummy node and 
			// scheduling a node structure changed event to reset the JTree.
			Swing.runLater(() -> fireNodeStructureChanged((GTreeNode) parent));
			return new InProgressGTreeNode();
		}
	}

	@Override
	public int getChildCount(Object parent) {
		GTreeNode gTreeParent = (GTreeNode) parent;
		return gTreeParent.getChildCount();
	}

	@Override
	public int getIndexOfChild(Object parent, Object child) {
		GTreeNode gTreeParent = (GTreeNode) parent;
		return gTreeParent.getIndexOfChild((GTreeNode) child);
	}

	@Override
	public boolean isLeaf(Object node) {
		GTreeNode gTreeNode = (GTreeNode) node;
		return gTreeNode.isLeaf();
	}

	@Override
	public void valueForPathChanged(TreePath path, Object newValue) {
		GTreeNode node = (GTreeNode) path.getLastPathComponent();
		node.valueChanged(newValue);
	}

	public void fireNodeStructureChanged(final GTreeNode changedNode) {
		if (!eventsEnabled || isFiringNodeStructureChanged) {
			return;
		}
		try {
			isFiringNodeStructureChanged = true;
			SystemUtilities.assertThisIsTheSwingThread(
				"GTreeModel.fireNodeStructuredChanged() must be " + "called from the AWT thread");

			GTreeNode node = convertToViewNode(changedNode);
			if (node == null) {
				return;
			}
			if (node != changedNode) {
				node.setChildren(null);
				return;	// the previous call will generate the proper event, so bail
			}

			TreeModelEvent event = new TreeModelEvent(this, node.getTreePath());
			for (TreeModelListener listener : listeners) {
				listener.treeStructureChanged(event);
			}
		}
		finally {
			isFiringNodeStructureChanged = false;
		}
	}

	public void fireRootChanged() {
		if (!eventsEnabled) {
			return;
		}
		Swing.runIfSwingOrRunLater(() -> {
			GTreeNode rootNode = root;
			if (rootNode != null) {
				fireNodeStructureChanged(rootNode);
			}
		});
	}

	public void fireNodeDataChanged(GTreeNode changedNode) {
		if (!eventsEnabled) {
			return;
		}
		SystemUtilities.assertThisIsTheSwingThread(
			"GTreeModel.fireNodeDataChanged() must be " + "called from the AWT thread");

		TreeModelEvent event = getChangedNodeEvent(changedNode);

		for (TreeModelListener listener : listeners) {
			listener.treeNodesChanged(event);
		}
	}

	public void fireNodeAdded(final GTreeNode parentNode, final GTreeNode newNode) {
		if (!eventsEnabled) {
			return;
		}
		SystemUtilities.assertThisIsTheSwingThread(
			"GTreeModel.fireNodeAdded() must be " + "called from the AWT thread");

		GTreeNode parent = convertToViewNode(parentNode);
		if (parent == null) {  // it will be null if filtered out
			return;
		}

		int index = parent.getIndexOfChild(newNode);
		if (index < 0) {
			// the index will be -1 if filtered out
			return;
		}

		TreeModelEvent event = new TreeModelEvent(this, parent.getTreePath(), new int[] { index },
			new Object[] { newNode });
		for (TreeModelListener listener : listeners) {
			listener.treeNodesInserted(event);
		}
	}

	public void fireNodeRemoved(GTreeNode parentNode, GTreeNode removedNode, int index) {

		SystemUtilities.assertThisIsTheSwingThread(
			"GTreeModel.fireNodeRemoved() must be " + "called from the AWT thread");

		GTreeNode parent = convertToViewNode(parentNode);
		if (parent == null) {  // will be null if filtered out
			return;
		}

		// if filtered, remove filtered node
		if (parent != parentNode) {
			index = removeFromFiltered(parent, removedNode);
			return;  // the above call will generate the event for the filtered node
		}

		if (index < 0) {  // will be -1 if filtered out
			return;
		}

		TreeModelEvent event = new TreeModelEvent(this, parent.getTreePath(), new int[] { index },
			new Object[] { removedNode });

		for (TreeModelListener listener : listeners) {
			listener.treeNodesRemoved(event);
		}
	}

	public void dispose() {
		root = null;
	}

	public void setEventsEnabled(boolean b) {
		eventsEnabled = b;
	}

	private TreeModelEvent getChangedNodeEvent(GTreeNode changedNode) {
		GTreeNode parentNode = changedNode.getParent();
		if (parentNode == null) { // tree requires different event form when it is the root that changes
			return new TreeModelEvent(this, root.getTreePath(), null, null);
		}

		GTreeNode node = convertToViewNode(changedNode);
		if (node == null) {
			return null;
		}

		int indexInParent = node.getIndexInParent();
		if (indexInParent < 0) {
			return null;
		}

		return new TreeModelEvent(this, node.getParent().getTreePath(), new int[] { indexInParent },
			new Object[] { changedNode });

	}

	private GTreeNode convertToViewNode(GTreeNode node) {
		if (node.getRoot() == root) {
			return node;
		}
		GTree tree = root.getTree();
		if (tree != null) {
			return tree.getViewNodeForPath(node.getTreePath());
		}
		return null;
	}

	private int removeFromFiltered(GTreeNode parent, GTreeNode removedNode) {
		int index = parent.getIndexOfChild(removedNode);
		if (index >= 0) {
			parent.removeNode(removedNode);
		}
		return index;
	}
}
