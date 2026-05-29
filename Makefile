.PHONY: all compile test clean verilog help

# Default target
all: compile

# Compile the project
compile:
	sbt compile

# Run all tests
test:
	sbt test

# Run a specific test (usage: make test-module MODULE=PE)
test-module:
	sbt "testOnly accelerator.$(MODULE)Spec"

# Generate SystemVerilog
verilog:
	sbt "runMain accelerator.TopMain"

# Clean build artifacts
clean:
	sbt clean
	rm -rf target project/target project/project test_run_dir generated

# Format code
format:
	sbt scalafmt

# Check formatting
format-check:
	sbt scalafmtCheck

# Open SBT console
console:
	sbt console

# Show dependency tree
deps:
	sbt dependencyTree

# Help target
help:
	@echo "Available targets:"
	@echo "  compile      - Compile the project"
	@echo "  test         - Run all tests"
	@echo "  test-module  - Run specific test (e.g., make test-module MODULE=PE)"
	@echo "  verilog      - Generate Verilog output"
	@echo "  clean        - Clean build artifacts"
	@echo "  format       - Format code with scalafmt"
	@echo "  format-check - Check code formatting"
	@echo "  console      - Open SBT console"
	@echo "  deps         - Show dependency tree"
	@echo "  help         - Show this help message"
