set -e
./gradlew --stop
./gradlew clean build check assemble
set +e
./gradlew -x check -x test -x integTest binTray --no-daemon  --max-workers=1
./gradlew -x check -x test -x integTest uploadArchives closeAndReleaseRepository --no-daemon  --max-workers=1

