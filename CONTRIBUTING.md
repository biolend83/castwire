# Contributing

Bug reports with the device model ("Chromecast with Google TV", the TV maker and model) and the
`-v` log of the sample app are the most useful thing you can send.

## Pull requests and the contributor agreement

Castwire is offered under the AGPL-3.0 and under a commercial license (see [COMMERCIAL.md](COMMERCIAL.md)).
To keep offering both, every contribution needs this agreement. Add the line below to your pull request
description; the pull request can't be merged without it.

> I grant biolend83 a perpetual, worldwide, non-exclusive, royalty-free, irrevocable license to use,
> copy, modify, sublicense and distribute my contribution under any license, including the AGPL-3.0
> and commercial licenses. I wrote this contribution myself (or have the right to submit it), and it
> may be distributed under those terms.

You keep the copyright of what you contribute; the agreement only lets it be shipped under both
licenses.

## Code

- No new dependencies in `castwire` beyond kotlinx-coroutines.
- Java 8 bytecode: no APIs above Android API 24.
- `./gradlew :castwire:test` must pass.
