# Codewind GitHub Bot

You can now use the following GitHub commands in the `eclipse/codewind-*` repositories, in order to set labels, pipelines, status, or assignees. Commands can be specified (multiple at time) in either the issue description, or in an issue comment. See [this issue for examples](https://github.com/eclipse/codewind/issues/844).

The command format and primary commands are the same as used as by [Kubernetes Prow](https://github.com/kubernetes/test-infra/tree/master/prow) [bot](https://github.com/kubernetes/test-infra/commits?author=k8s-ci-robot). Though in some cases the Codewind bot will accept more liberal syntax than Prow, when the user intent is clear (for example, both `/assign @user` and `/assign user` are supported).

## Bot Command Quick Reference

#### Assign an issue
- /assign `@user`
- /unassign `@user`

#### Add/remove area, kind, and priority

Areas:
- /area `(appsody/iterative-dev/openapi/portal)`
- /area `(eclipse-ide/intellij-ide/vscode-ide)`
- /area `(community/design/docs/releng/website)`

/kind `(enhancement/bug/question/test)`

/priority `(build-break/hot/next release/stopship)`

#### Remove label:
- /remove-area `(existing area label)`
- /remove-kind `(existing kind label)`
- /remove-priority `(existing priority label)`


### Add/remove a single label

#### Single labels:
- /tech-topic
- /good-first-issue
- /wontfix
- /svt
- /epic

#### Remove single labels:
- /remove-tech-topic
- /remove-good-first-issue
- /remove-wontfix
- /remove-svt
- /remove-epic


### Change issue status

Pipelines:
- /pipeline `(New Issues/Epics/Backlog/In Progress/Waiting for design/Waiting for backend/Icebox/Verify/Done)`
- /pipeline `(Iterative-dev Backlog/Portal backlog/Extensions Backlog)`

#### Releases:
- /release `(0.8.1/0.9.0/0.10.0/etc)`
- /remove-release `(release)`

#### Statuses:
- /reopen
- /close
- /verify



## Detailed Examples 

### Commands


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
