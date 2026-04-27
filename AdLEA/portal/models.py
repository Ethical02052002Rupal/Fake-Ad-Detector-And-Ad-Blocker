from django.db import models
from django.contrib.auth.models import User


class BlacklistedAd(models.Model):
    user = models.ForeignKey(User, on_delete=models.CASCADE)

    title = models.TextField(blank=True, null=True)
    body = models.TextField(blank=True, null=True)
    link_url = models.URLField(blank=True, null=True)
    image_url = models.URLField(blank=True, null=True)

    created_at = models.DateTimeField(auto_now_add=True)

    reviewed = models.BooleanField(default=False)

    def __str__(self):
        return self.title if self.title else "Blacklisted Ad"