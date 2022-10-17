package com.virjar.ratel.api.extension.superappium.traversor;


import com.virjar.ratel.api.extension.superappium.ViewImage;

public interface NodeVisitor {
    /**
     * Callback for when a node is first visited.
     *
     * @param node  the node being visited.
     * @param depth the depth of the node, relative to the root node. E.g., the root node has depth 0, and a child node
     *              of that will have depth 1.
     */
    boolean head(ViewImage node, int depth);

    /**
     * Callback for when a node is last visited, after all of its descendants have been visited.
     *
     * @param node  the node being visited.
     * @param depth the depth of the node, relative to the root node. E.g., the root node has depth 0, and a child node
     *              of that will have depth 1.
     */
    boolean tail(ViewImage node, int depth);
}
