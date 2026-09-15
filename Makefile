# Five independently invocable checks. Pre-commit and Gitea jobs call these
# targets (not a single combined script). Emergency skip:
#   SKIP=local-tests,sast,audit,gitleaks,style git commit
# `make tools` only fetches JDK 27 + pinned CLIs (Gitea prepare stage).
.PHONY: test sast audit gitleaks style check hooks tools

test:
	$(CURDIR)/scripts/test.sh

sast:
	$(CURDIR)/scripts/sast.sh

audit:
	$(CURDIR)/scripts/audit.sh

gitleaks:
	$(CURDIR)/scripts/gitleaks.sh

style:
	$(CURDIR)/scripts/style.sh

check: test sast audit gitleaks style

tools:
	$(CURDIR)/scripts/tools.sh

hooks:
	pre-commit install
	git config core.hooksPath .githooks
