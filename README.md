# Codewind GitHub Bot

## Use GitHub issue/comments to assign labels, assignees on GitHub issues

### Commands

You can now use the following GitHub commands to set labels, or assignees. Commands can be specified (multiple at time) in either the issue description, or in an issue comment. See [this issue for examples](https://github.com/eclipse/codewind/issues/844).

#### Add a label:
```
/kind bug
/area iterative-dev
/priority hot
(for any kind, area, or priority)
```

#### Remove a label:
```
/remove-kind
/remove-area
/remove-priority

Example: /remove-priority hot 
```

#### Assign/unassign yourself or others to a issue:
```
/assign  (to assign yourself)
# or
/assign @jgwest (to assign someone else; BUT the target user needs to either be a committer, or to already have commented on the bug; this is a GitHub API limitation)
# or
/assign me

/unassign (same as above)
```

The command format (and exact commands) is the same format used as the [Kubernetes Prow](https://github.com/kubernetes/test-infra/tree/master/prow) [bot](https://github.com/kubernetes/test-infra/commits?author=k8s-ci-robot), which will allow us to move functionality to Prow once the infrastructure is in place.

#### Close/Reopen, Change Pipeline, Change Release

Close or reopen an issue:
```
/close
# or
/reopen
```

Change pipeline:
```
/pipeline In Progress
# or
/pipeline Verify
```
You can also optionally put quotes around the pipeline name if you wish, and pipeline names are _case insensitive_.

Add or remove a release:
```
/release 0.7.0
# or
/remove-release 0.7.0
```
