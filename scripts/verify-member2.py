from pathlib import Path
import urllib.request,urllib.error,json,uuid,subprocess,random
base='http://127.0.0.1:8080/api'
checks=0
def call(path,method='GET',body=None,token=None,expect=200):
 global checks
 headers={'Content-Type':'application/json'}
 if token: headers['Authorization']='Bearer '+token
 req=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
 try:
  with urllib.request.urlopen(req) as r: code=r.status; result=json.load(r)
 except urllib.error.HTTPError as e: code=e.code; result=json.loads(e.read() or b'{}')
 assert code==expect,(path,code,result)
 checks+=1
 return result.get('data')
def sql(query):
 p=Path(__file__).resolve().parents[1]/'.tools/pgsql/bin/psql.exe'
 args=[str(p),'-h','127.0.0.1','-p','55432','-U','postgres','-d','bcm_member2_test','-v','ON_ERROR_STOP=1','-c',query]
 return subprocess.run(args,capture_output=True,text=True,encoding='utf-8',check=True).stdout
# This verifier only targets the isolated local test database.
assert 'bcm_member2_test' in sql('select current_database();')
token=call('/auth/login','POST',{'email':'admin@bcm.com','password':'admin123'})['accessToken']
for kind in ['courts','time-slots','categories','products']:call('/public/'+kind)
call('/admin/courts',expect=403)
number=random.randint(10000,2000000000)
cbody={'courtNumber':number,'name':'API test court','type':'STANDARD_MAT','status':'AVAILABLE','basePrice':100000}
c=call('/admin/courts','POST',cbody,token,201)
call('/admin/courts','POST',cbody,token,409)
call('/admin/courts','POST',{**cbody,'courtNumber':number+1,'name':' ','basePrice':-1},token,400)
c=call('/admin/courts/'+c['id'],'PUT',{**cbody,'type':'WOODEN_FLOOR'},token)
slot=next(s for s in call('/public/time-slots') if s['startTime']=='17:00:00')
q=call('/public/price-quote?courtId='+c['id']+'&timeSlotId='+slot['id'])
assert q['price']==150000
call('/admin/time-slots','POST',{'startTime':'17:30','endTime':'18:30','priceMultiplier':1},token,409)
call('/admin/time-slots','POST',{'startTime':'22:00','endTime':'21:00'},token,400)
s=call('/admin/time-slots','POST',{'startTime':'05:00','endTime':'06:00'},token,201)
s=call('/admin/time-slots/'+s['id'],'PUT',{'startTime':'05:00','endTime':'06:00','priceMultiplier':1.25},token)
assert call('/public/price-quote?courtId='+c['id']+'&timeSlotId='+s['id'])['price']==125000
call('/admin/time-slots/'+s['id'],'DELETE',token=token)
category=call('/admin/categories','POST',{'categoryName':'API test '+str(uuid.uuid4())},token,201)
pbody={'categoryId':category['id'],'name':'API test product','type':'GOODS','unit':'chai','price':10000,'stockQuantity':20}
p=call('/admin/products','POST',pbody,token,201)
call('/admin/categories/'+category['id'],'DELETE',token=token,expect=400)
call('/admin/products/'+p['id'],'PUT',{**pbody,'version':99},token,409)
p=call('/admin/products/'+p['id'],'PUT',{**pbody,'price':15000,'version':p['version']},token)
call('/admin/products/'+p['id'],'PUT',{**pbody,'stockQuantity':21,'version':p['version']},token,400)
call('/admin/products','POST',{**pbody,'type':'SERVICE'},token,400)
call('/admin/products/'+p['id'],'DELETE',token=token)
call('/admin/categories/'+category['id'],'DELETE',token=token)
uid,customer,bid,detail=[str(uuid.uuid4()) for _ in range(4)]
sql(f"INSERT INTO users(id,email,password_hash,full_name,phone) VALUES ('{uid}','test-{uid}@example.invalid','test','API fixture','0'); INSERT INTO customers(id,user_id,full_name,phone) VALUES ('{customer}','{uid}','API fixture','0'); INSERT INTO bookings(id,customer_id,status,court_fee,expires_at) VALUES ('{bid}','{customer}','PENDING',150000,CURRENT_TIMESTAMP+interval '1 hour'); INSERT INTO booking_details(id,booking_id,court_id,time_slot_id,booking_date,price) VALUES ('{detail}','{bid}','{c['id']}','{slot['id']}',CURRENT_DATE,150000);")
for state in ['PENDING','PAID','CHECKED_IN']:
 sql(f"UPDATE bookings SET status='{state}' WHERE id='{bid}';")
 call('/admin/courts/'+c['id'],'DELETE',token=token,expect=400)
call('/admin/time-slots/'+slot['id'],'DELETE',token=token,expect=400)
sql(f"UPDATE bookings SET status='COMPLETED' WHERE id='{bid}';")
call('/admin/courts/'+c['id'],'DELETE',token=token)
assert '150000' in sql(f"SELECT price FROM booking_details WHERE id='{detail}';")
sql(f"DELETE FROM bookings WHERE id='{bid}'; DELETE FROM customers WHERE id='{customer}'; DELETE FROM users WHERE id='{uid}';")
print(f'PASS: {checks} live HTTP checks on PostgreSQL, including JWT and historical price preservation')
