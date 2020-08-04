fun createDiff(old: Tree?, new: Tree):
  val inserted, modified = Map<String, TreeDiff>()

  if oldTree != null:
    for newChild in new.children:
      val oldChild = old.children[newChild.name]
      val diff = createDiff(oldChild, newChild)
      if oldChild == null:
        inserted[diff.name] = diff
      else if oldChild != newChild:
        modified[diff.name] = diff
    return TreeDiff(old, new, inserted, modified, deleted)
  else:
    for child in newTree.children:
      inserted[child.name] = createDiff(null, child)
    return TreeDiff(copy(new), new, inserted, modified, deleted)
