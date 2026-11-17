@echo off
rem Archive committed source without deleting a temporary directory.
git archive --format=zip --output=S-Camera-source.zip HEAD
