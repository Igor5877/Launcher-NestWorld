#!/bin/bash
JVM_ARGS=""
while read -r line; do
  if [[ "$line" == "cpw.mods.bootstraplauncher.BootstrapLauncher" ]]; then
    break
  fi
  # Skip empty lines
  if [[ -z "$line" ]]; then
    continue
  fi
  JVM_ARGS="$JVM_ARGS $line"
done < dummy_unix_args.txt

echo "Extracted JVM Args:"
echo "$JVM_ARGS"

if [[ "$JVM_ARGS" == *"cpw.mods.bootstraplauncher.BootstrapLauncher"* ]]; then
  echo "FAIL: Main class found in JVM args!"
  exit 1
fi

if [[ "$JVM_ARGS" != *"-p libraries/cpw/mods"* ]]; then
  echo "FAIL: Module path missing!"
  exit 1
fi

if [[ "$JVM_ARGS" == *"--launchTarget"* ]]; then
  echo "FAIL: Program arg found in JVM args!"
  exit 1
fi

echo "SUCCESS: Correctly parsed JVM args."
