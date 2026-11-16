p=r"C:\Users\65401\Downloads\xh_work\iosduo\repo\README.md"
s=open(p,encoding='utf-8',newline='').read()
s=s.replace("1. ?? [`release/IOSDuo-ThreeInOne-v2.5-rb.apk`](release/)",
            "1. ?? [Releases ??????](../../releases/latest)????? `release/` ???")
s=s.replace("?? **v2.5-rb**?versionCode 25?","?? **v2.7-rb**?versionCode 27?")
open(p,'w',encoding='utf-8',newline='').write(s)
print("readme updated")
