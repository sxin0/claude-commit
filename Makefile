# Claude Code Commit — build
#
# Usage:
#   make            # compile + package claude-commit-plugin.jar
#   make clean      # remove build output and the jar
#   make IDE_HOME="/path/to/IDE.app/Contents" ...   # override IDE location
#
# kotlinc does NOT expand the `lib/*` wildcard the way `java -cp` does, so the
# classpath is expanded to an explicit list of jars below.

PLUGIN_JAR := claude-commit-plugin.jar
SRC        := $(wildcard src/main/kotlin/com/github/claudecommit/*.kt)
OUT        := out
JVM_TARGET := 21

# Auto-detect a JetBrains IDE that bundles kotlinc + the platform jars.
# Override on the command line if detection fails, e.g.:
#   make IDE_HOME="$$HOME/Applications/IntelliJ IDEA.app/Contents"
IDE_HOME ?= $(shell for d in "$$HOME/Applications"/*.app/Contents /Applications/*.app/Contents; do \
	  [ -x "$$d/plugins/Kotlin/kotlinc/bin/kotlinc" ] && { echo "$$d"; break; }; \
	done)

KOTLINC := $(IDE_HOME)/plugins/Kotlin/kotlinc/bin/kotlinc

.PHONY: all build jar clean check

all: jar

check:
	@[ -n "$(IDE_HOME)" ] || { \
	  echo "ERROR: no JetBrains IDE with a bundled kotlinc was found."; \
	  echo "       Set IDE_HOME explicitly, e.g.:"; \
	  echo '       make IDE_HOME="$$HOME/Applications/IntelliJ IDEA.app/Contents"'; \
	  exit 1; }
	@[ -x "$(KOTLINC)" ] || { echo "ERROR: kotlinc not found at $(KOTLINC)"; exit 1; }

# Compile Kotlin sources against the IDE platform classpath.
# lib/modules is included because newer platforms split some APIs (e.g. VCS)
# into per-module jars there.
# Falls back to the IDE's bundled JBR (JDK 21) when JAVA_HOME is unset.
build: check
	@rm -rf $(OUT) && mkdir -p $(OUT)
	@echo "Using IDE: $(IDE_HOME)"
	@CP=$$(find "$(IDE_HOME)/lib" "$(IDE_HOME)/lib/modules" "$(IDE_HOME)/plugins/terminal/lib" "$(IDE_HOME)/plugins/terminal/lib/modules" -maxdepth 1 -name '*.jar' 2>/dev/null | tr '\n' ':'); \
	 JH="$${JAVA_HOME:-$(IDE_HOME)/jbr/Contents/Home}"; \
	 JAVA_HOME="$$JH" "$(KOTLINC)" $(SRC) -classpath "$$CP" -d $(OUT) -jvm-target $(JVM_TARGET)

# Package the plugin jar (compiled classes + plugin.xml + icons + i18n bundles).
# Same JAVA_HOME fallback as `build`: works without a system JDK on the PATH.
jar: build
	@cp -r src/main/resources/META-INF src/main/resources/icons src/main/resources/messages $(OUT)/
	@JH="$${JAVA_HOME:-$(IDE_HOME)/jbr/Contents/Home}"; \
	 cd $(OUT) && "$$JH/bin/jar" cf ../$(PLUGIN_JAR) META-INF/ icons/ messages/ com/
	@rm -rf $(OUT)
	@echo "Built $(PLUGIN_JAR)"

clean:
	@rm -rf $(OUT) $(PLUGIN_JAR)
	@echo "Cleaned"
