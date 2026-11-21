"""Short localized receipts for verified tool outcomes, without another LLM turn."""

TEXT={
 'te':{
  'memory_save':'గుర్తుంచుకున్నాను.', 'task_create':'పని సేవ్ చేశాను.',
  'task_update':'పని వివరాలు మార్చాను.', 'task_complete':'పని పూర్తయినట్లు గుర్తించాను.',
  'task_reopen':'పనిని మళ్లీ తెరిచాను.', 'memory_forget':'ఆ జ్ఞాపకాన్ని తొలగించాను.',
  'open_app':'యాప్ తెరవమని ఫోన్‌కు పంపాను.', 'search_youtube':'యూట్యూబ్‌లో వెతకమని ఫోన్‌కు పంపాను.',
  'cancel_question':'ఆ ప్రశ్నను రద్దు చేశాను.'},
 'en':{
  'memory_save':"I'll remember that.", 'task_create':'Task saved.',
  'task_update':'Task updated.', 'task_complete':'Marked the task complete.',
  'task_reopen':'Reopened the task.', 'memory_forget':'That memory is deleted.',
  'open_app':'Asked your phone to open the app.', 'search_youtube':'Asked your phone to search YouTube.',
  'cancel_question':'Cancelled that question.'},
 'hi':{
  'memory_save':'याद रख लिया।', 'task_create':'काम सेव कर दिया।',
  'task_update':'काम की जानकारी बदल दी।', 'task_complete':'काम पूरा चिह्नित कर दिया।',
  'task_reopen':'काम फिर से खोल दिया।', 'memory_forget':'वह याद मिटा दी।',
  'open_app':'फ़ोन को ऐप खोलने का अनुरोध भेज दिया।', 'search_youtube':'फ़ोन को यूट्यूब पर खोजने का अनुरोध भेज दिया।',
  'cancel_question':'वह सवाल रद्द कर दिया।'}
}
STATUS={'memory_save':{'remembered'},'task_create':{'saved'},'task_update':{'updated'},
 'task_complete':{'updated','already_done'},'task_reopen':{'updated','already_open'},
 'memory_forget':{'forgotten'},'open_app':{'launch_requested'},'search_youtube':{'launch_requested'},
 'cancel_question':{'cancelled'}}

def receipt(outcomes,locale):
 language=TEXT.get(locale.split('-')[0])
 if not language or not outcomes:return None
 lines=[]
 for outcome in outcomes:
  tool=outcome['tool'];result=outcome['result']
  if result.get('status') not in STATUS.get(tool,set()):return None
  if tool in ('task_complete','task_reopen'):
   if result.get('task',{}).get('status')!=('done' if tool=='task_complete' else 'open'):return None
  lines.append(language[tool])
 return ' '.join(dict.fromkeys(lines))
